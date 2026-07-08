package com.im.core.dispatcher;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.exception.PersistenceException;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.RequestObservability;
import com.im.core.observability.StructuredLog;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协议无关的统一请求调度器（类似 Spring MVC 的 DispatcherServlet）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>维护 {@code operation → RequestHandler} 映射</li>
 *   <li>维护拦截器链</li>
 *   <li>执行 preHandle → handler → afterComplete + 全局异常处理</li>
 * </ul>
 *
 * <p>WS 和 HTTP 的 Adapter 在 EventLoop 线程中创建 {@link ApiRequest}，
 * 提交到虚拟线程池后调用此调度器。</p>
 */
public class ApiDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ApiDispatcher.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("im-system");

    private final Map<String, RequestHandler> handlerMap = new ConcurrentHashMap<>();
    private final List<ApiInterceptor> interceptors = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Throwable>, ApiExceptionHandler> exceptionHandlers = new LinkedHashMap<>();

    public ApiDispatcher() {
    }

    /**
     * 注册自定义异常处理器（按 class 精确匹配 + 继承链查找）。
     *
     * @param type    异常类型（如 {@code RetryExecutionException.class}）
     * @param handler 自定义处理逻辑
     */
    public ApiDispatcher registerExceptionHandler(Class<? extends Throwable> type, ApiExceptionHandler handler) {
        exceptionHandlers.put(type, handler);
        log.info("ApiExceptionHandler registered: {}", type.getSimpleName());
        return this;
    }

    // ── 注册 ──

    /**
     * 【新】通过 Operation 枚举注册业务处理器。
     *
     * @param operation Operation 枚举值
     * @param handler   处理器实例
     */
    public ApiDispatcher registerHandler(Operation operation, RequestHandler handler) {
        String opName = operation.opName();
        if (handlerMap.containsKey(opName)) {
            log.warn("Duplicate handler for operation '{}', overwriting", opName);
        }
        handlerMap.put(opName, handler);
        log.debug("Handler registered: {} -> {}", opName, handler.getClass().getSimpleName());
        return this;
    }

    /**
     * 【新】用同一个 handler 实例注册多个 Operation。
     */
    public ApiDispatcher registerHandlers(RequestHandler handler, Operation... operations) {
        for (Operation op : operations) {
            registerHandler(op, handler);
        }
        return this;
    }


    /** 注册拦截器（按 order 自动排序） */
    public ApiDispatcher addInterceptor(ApiInterceptor interceptor) {
        interceptors.add(interceptor);
        interceptors.sort(Comparator.comparingInt(ApiInterceptor::order));
        log.info("Interceptor registered: {} (order={})", interceptor.name(), interceptor.order());
        return this;
    }

    /** 返回当前注册的所有 operation */
    public List<String> registeredOperations() {
        return List.copyOf(handlerMap.keySet());
    }

    // ── 调度 ──

    /**
     * 分发请求并执行拦截器链 + handler + 异常处理。
     *
     * <p>由 Adapter 在虚拟线程中调用。</p>
     */
    public void dispatch(ApiRequest request) {
        String operation = request.operation();
        long startNanos = System.nanoTime();
        DispatchOutcome outcome = null;

        Span span = TRACER.spanBuilder(operation).startSpan();
        Scope spanScope = span.makeCurrent();
        RequestObservability.Scope mdcScope = RequestObservability.bindMdc(request);
        try {
            RequestHandler handler = handlerMap.get(operation);
            if (handler == null) {
                log.warn(StructuredLog.event(LogEvents.HANDLER_MISSING,
                        LogFields.OPERATION, operation,
                        LogFields.ERROR_CODE, ImErrorCode.NOT_FOUND.getCode()));
                request.responseWriter().writeError(ImErrorCode.NOT_FOUND, "no handler for: " + operation);
                outcome = DispatchOutcome.failure(ImErrorCode.NOT_FOUND, null, "handler_missing");
                return;
            }
            outcome = process(request, handler);
        } catch (Exception e) {
            span.recordException(e);
            if (outcome == null) {
                outcome = DispatchOutcome.failure(ImErrorCode.INTERNAL_ERROR, e, "dispatch_exception");
            }
            throw e;
        } finally {
            logCompletion(request, outcome, startNanos);
            mdcScope.close();
            spanScope.close();
            span.end();
        }
    }

    private DispatchOutcome process(ApiRequest request, RequestHandler handler) {
        int idx = 0;
        // ── preHandle 链 ──
        for (; idx < interceptors.size(); idx++) {
            ApiInterceptor interceptor = interceptors.get(idx);
            try {
                if (!interceptor.preHandle(request)) {
                    log.warn(StructuredLog.event(LogEvents.INTERCEPTOR_REJECTED,
                            LogFields.INTERCEPTOR, interceptor.name(),
                            LogFields.OPERATION, request.operation(),
                            LogFields.ERROR_CODE, ImErrorCode.FORBIDDEN.getCode(),
                            LogFields.REASON, "returned_false"));
                    afterCompleteReverse(request, idx, null, null);
                    // 这里是不是应该先写会结果在执行拦截器的after
                    request.responseWriter().writeError(ImErrorCode.FORBIDDEN,
                            "blocked by interceptor: " + interceptor.name());
                    return DispatchOutcome.failure(ImErrorCode.FORBIDDEN, null, "blocked_by_" + interceptor.name());
                }
            } catch (ImException e) {
                log.warn(StructuredLog.event(LogEvents.INTERCEPTOR_REJECTED,
                        LogFields.INTERCEPTOR, interceptor.name(),
                        LogFields.OPERATION, request.operation(),
                        LogFields.ERROR_CODE, e.getErrorCode().getCode(),
                        LogFields.DETAIL, e.getSafeMessage()));
                afterCompleteReverse(request, idx, null, e);
                writeImError(request, e);
                return DispatchOutcome.failure(e.getErrorCode(), e, "interceptor_rejected");
            } catch (Exception e) {
                log.error(StructuredLog.event(LogEvents.INTERCEPTOR_FAILED,
                        LogFields.INTERCEPTOR, interceptor.name(),
                        LogFields.OPERATION, request.operation(),
                        LogFields.ERROR_CODE, ImErrorCode.INTERNAL_ERROR.getCode(),
                        LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()), e);
                afterCompleteReverse(request, idx, null, e);
                request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR,
                        null);
                return DispatchOutcome.failure(ImErrorCode.INTERNAL_ERROR, e, "interceptor_failed");
            }
        }

        // ── handler 执行 ──
        Object result = null;
        Exception handlerEx = null;
        try {
            result = handler.handle(request);
        } catch (ImException e) {
            handlerEx = e;
            logHandlerException(request, e);
            writeImError(request, e);
            return DispatchOutcome.failure(e.getErrorCode(), e, "handler_rejected");
        } catch (Exception e) {
            handlerEx = e;
            ApiExceptionHandler customHandler = findExceptionHandler(e.getClass());
            if (customHandler != null) {
                log.warn(StructuredLog.event(LogEvents.HANDLER_FAILED,
                        LogFields.OPERATION, request.operation(),
                        LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName(),
                        LogFields.REASON, "custom_exception_handler_selected"));
                try {
                    customHandler.handle(e, request);
                } catch (Exception customEx) {
                    log.error(StructuredLog.event(LogEvents.EXCEPTION_HANDLER_FAILED,
                            LogFields.OPERATION, request.operation(),
                            LogFields.HANDLER, customHandler.getClass().getSimpleName(),
                            LogFields.EXCEPTION_CLASS, customEx.getClass().getSimpleName()), customEx);
                    request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
                    return DispatchOutcome.failure(ImErrorCode.INTERNAL_ERROR, customEx, "custom_exception_handler_failed");
                }
            } else {
                log.error(StructuredLog.event(LogEvents.HANDLER_FAILED,
                        LogFields.OPERATION, request.operation(),
                        LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName(),
                        LogFields.REASON, "unhandled_exception"), e);
                request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
            }
            return DispatchOutcome.failure(ImErrorCode.INTERNAL_ERROR, e, customHandler != null
                    ? "handler_exception_custom_handled" : "handler_exception");
        } finally {
            afterCompleteReverse(request, idx, result, handlerEx);
        }

        // handler 正常返回且未自行写响应时，由 ResponseWriter 自动序列化
        if (handlerEx == null && !request.responseWriter().isCommitted()) {
            Object response = result;
            request.responseWriter().write(response);
        }
        return DispatchOutcome.ok();
    }

    /** 反序回调已通过的拦截器的 afterCompletion */
    private void afterCompleteReverse(ApiRequest request, int passedCount, Object result, Exception error) {
        for (int i = passedCount - 1; i >= 0; i--) {
            try {
                interceptors.get(i).afterCompletion(request, result, error);
            } catch (Exception e) {
                log.warn(StructuredLog.event(LogEvents.AFTER_COMPLETION_FAILED,
                        LogFields.INTERCEPTOR, interceptors.get(i).name(),
                        LogFields.OPERATION, request.operation(),
                        LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()));
            }
        }
    }

    private void writeImError(ApiRequest request, ImException e) {
        String detail = e.isClientVisible() ? e.getSafeMessage() : null;
        request.responseWriter().writeError(e.getErrorCode(), detail);
    }

    private void logHandlerException(ApiRequest request, ImException e) {
        String event = e instanceof PersistenceException ? LogEvents.HANDLER_FAILED : LogEvents.HANDLER_REJECTED;
        String line = StructuredLog.event(event,
                LogFields.OPERATION, request.operation(),
                LogFields.ERROR_CODE, e.getErrorCode().getCode(),
                LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName(),
                LogFields.DETAIL, e.isClientVisible() ? e.getSafeMessage() : null);
        if (e instanceof PersistenceException) {
            log.error(line, e);
        } else {
            log.warn(line);
        }
    }

    private void logCompletion(ApiRequest request, DispatchOutcome outcome, long startNanos) {
        DispatchOutcome finalOutcome = outcome != null ? outcome : DispatchOutcome.failure(
                ImErrorCode.INTERNAL_ERROR, null, "unknown_dispatch_outcome");
        long latencyMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        String event = finalOutcome.success ? LogEvents.REQUEST_COMPLETED : LogEvents.REQUEST_FAILED;
        String line = StructuredLog.event(event,
                LogFields.NODE_ID, RequestObservability.nodeId(request),
                LogFields.REQUEST_ID, RequestObservability.requestId(request),
                LogFields.TRACE_ID, RequestObservability.traceId(request),
                LogFields.USER_ID, RequestObservability.userId(request),
                LogFields.OPERATION, RequestObservability.operation(request),
                LogFields.PROTOCOL, RequestObservability.protocol(request),
                LogFields.CLIENT_IP, RequestObservability.clientIp(request),
                LogFields.CONNECTION_ID, RequestObservability.attr(request, ApiRequest.ATTR_CONNECTION_ID),
                LogFields.WS_SEQ, RequestObservability.attr(request, ApiRequest.ATTR_WS_SEQ),
                LogFields.HTTP_METHOD, RequestObservability.attr(request, ApiRequest.ATTR_HTTP_METHOD),
                LogFields.HTTP_PATH, RequestObservability.attr(request, ApiRequest.ATTR_HTTP_PATH),
                LogFields.LATENCY_MS, latencyMs,
                LogFields.SUCCESS, finalOutcome.success,
                LogFields.ERROR_CODE, finalOutcome.errorCode != null ? finalOutcome.errorCode.getCode() : null,
                LogFields.EXCEPTION_CLASS, finalOutcome.exceptionClass,
                LogFields.REASON, finalOutcome.reason);
        if (finalOutcome.errorCode != null && finalOutcome.errorCode.getCode() >= 500) {
            log.error(line);
        } else if (finalOutcome.errorCode == ImErrorCode.UNAUTHORIZED || finalOutcome.errorCode == ImErrorCode.RATE_LIMITED) {
            log.warn(line);
        } else {
            log.info(line);
        }
    }

    /** 按异常类型的继承链查找匹配的 ApiExceptionHandler。 */
    private ApiExceptionHandler findExceptionHandler(Class<? extends Throwable> exceptionClass) {
        for (Class<?> cls = exceptionClass; cls != null && cls != Throwable.class; cls = cls.getSuperclass()) {
            ApiExceptionHandler handler = exceptionHandlers.get(cls);
            if (handler != null) return handler;
        }
        return null;
    }

    private record DispatchOutcome(boolean success, ImErrorCode errorCode, String exceptionClass, String reason) {
        private static DispatchOutcome ok() {
            return new DispatchOutcome(true, null, null, null);
        }

        private static DispatchOutcome failure(ImErrorCode errorCode, Throwable error, String reason) {
            return new DispatchOutcome(false, errorCode,
                    error != null ? error.getClass().getSimpleName() : null,
                    reason);
        }
    }
}
