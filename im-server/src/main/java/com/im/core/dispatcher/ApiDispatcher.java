package com.im.core.dispatcher;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.common.exception.PersistenceException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
    private final Map<Class<? extends Throwable>, ExceptionHandler> exceptionHandlers = new LinkedHashMap<>();

    /**
     * 自定义异常处理器，用于为特定异常类型定制错误响应。
     *
     * <p>注册后，handler 执行中抛出该类型异常时优先调用此处理器。
     * 处理器需自行通过 {@link ApiRequest#responseWriter()} 写回响应。
     * 查找时按继承链向上匹配（子类 → 父类）。</p>
     */
    @FunctionalInterface
    public interface ExceptionHandler {
        void handle(Exception e, ApiRequest request);
    }

    public ApiDispatcher() {
    }

    /**
     * 注册自定义异常处理器（按 class 精确匹配 + 继承链查找）。
     *
     * @param type    异常类型（如 {@code RetryExecutionException.class}）
     * @param handler 自定义处理逻辑
     */
    public ApiDispatcher registerExceptionHandler(Class<? extends Throwable> type, ExceptionHandler handler) {
        exceptionHandlers.put(type, handler);
        log.info("ExceptionHandler registered: {}", type.getSimpleName());
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

        Span span = TRACER.spanBuilder(operation).startSpan();
        try (Scope scope = span.makeCurrent()) {
            bindMdc(request);
            RequestHandler handler = handlerMap.get(operation);
            if (handler == null) {
                log.warn("No handler for operation: {}", operation);
                request.responseWriter().writeError(ImErrorCode.NOT_FOUND, "no handler for: " + operation);
                return;
            }
            process(request, handler);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            clearMdc();
            span.end();
        }
    }

    private void bindMdc(ApiRequest request) {
        Object requestId = request.attribute(ApiRequest.ATTR_REQUEST_ID);
        if (requestId != null) {
            MDC.put("request_id", requestId.toString());
        }
        MDC.put("app.operation", request.operation());
        Object userId = request.attribute(ApiRequest.ATTR_USER_ID);
        if (userId != null) {
            MDC.put("app.user.id", userId.toString());
        }
        Object connectionId = request.attribute(ApiRequest.ATTR_CONNECTION_ID);
        if (connectionId != null) {
            MDC.put("connection_id", connectionId.toString());
        }
        Object wsSeq = request.attribute(ApiRequest.ATTR_WS_SEQ);
        if (wsSeq != null) {
            MDC.put("ws.seq", wsSeq.toString());
        }
    }

    private void clearMdc() {
        MDC.remove("request_id");
        MDC.remove("app.operation");
        MDC.remove("app.user.id");
        MDC.remove("connection_id");
        MDC.remove("ws.seq");
    }

    private void process(ApiRequest request, RequestHandler handler) {
        int idx = 0;
        // ── preHandle 链 ──
        for (; idx < interceptors.size(); idx++) {
            ApiInterceptor interceptor = interceptors.get(idx);
            try {
                if (!interceptor.preHandle(request)) {
                    log.debug("Interceptor '{}' blocked request op={}", interceptor.name(), request.operation());
                    afterCompleteReverse(request, idx, null, null);
                    // 这里是不是应该先写会结果在执行拦截器的after
                    request.responseWriter().writeError(ImErrorCode.FORBIDDEN,
                            "blocked by interceptor: " + interceptor.name());
                    return;
                }
            } catch (ImException e) {
                log.warn("Interceptor '{}' preHandle rejected: {} {}", interceptor.name(),
                        e.getErrorCode().getCode(), e.getDetail());
                afterCompleteReverse(request, idx, null, e);
                writeImError(request, e);
                return;
            } catch (Exception e) {
                log.warn("Interceptor '{}' preHandle threw", interceptor.name(), e);
                afterCompleteReverse(request, idx, null, e);
                request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR,
                        null);
                return;
            }
        }

        // ── handler 执行 ──
        Object result = null;
        Exception handlerEx = null;
        try {
            result = handler.handle(request);
        } catch (ImException e) {
            handlerEx = e;
            if (e instanceof PersistenceException) {
                log.error("Handler persistence failure: {} {} op={}", e.getCode(), e.getMessage(), request.operation(), e);
            } else {
                log.warn("Handler rejected: {} {} op={}", e.getCode(), e.getMessage(), request.operation());
            }
            writeImError(request, e);
        } catch (Exception e) {
            handlerEx = e;
            ExceptionHandler customHandler = findExceptionHandler(e.getClass());
            if (customHandler != null) {
                log.warn("Handler error handled by custom handler: op={}, ex={}",
                        request.operation(), e.getClass().getSimpleName());
                try {
                    customHandler.handle(e, request);
                } catch (Exception customEx) {
                    log.error("Custom exception handler failed: op={}, ex={}",
                            request.operation(), customEx.getClass().getSimpleName(), customEx);
                    request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
                }
            } else {
                log.error("Handler error: op={}", request.operation(), e);
                request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, null);
            }
        } finally {
            afterCompleteReverse(request, idx, result, handlerEx);
        }

        // handler 正常返回且未自行写响应时，由 ResponseWriter 自动序列化
        if (handlerEx == null && !request.responseWriter().isCommitted()) {
            Object response = result;
            request.responseWriter().write(response);
        }
    }

    /** 反序回调已通过的拦截器的 afterCompletion */
    private void afterCompleteReverse(ApiRequest request, int passedCount, Object result, Exception error) {
        for (int i = passedCount - 1; i >= 0; i--) {
            try {
                interceptors.get(i).afterCompletion(request, result, error);
            } catch (Exception e) {
                log.warn("Interceptor '{}' afterCompletion threw: {}", interceptors.get(i).name(), e.getMessage());
            }
        }
    }

    private void writeImError(ApiRequest request, ImException e) {
        String detail = e.isClientVisible() ? e.getSafeMessage() : null;
        request.responseWriter().writeError(e.getErrorCode(), detail);
    }

    /** 按异常类型的继承链查找匹配的 ExceptionHandler。 */
    private ExceptionHandler findExceptionHandler(Class<? extends Throwable> exceptionClass) {
        for (Class<?> cls = exceptionClass; cls != null && cls != Throwable.class; cls = cls.getSuperclass()) {
            ExceptionHandler handler = exceptionHandlers.get(cls);
            if (handler != null) return handler;
        }
        return null;
    }
}
