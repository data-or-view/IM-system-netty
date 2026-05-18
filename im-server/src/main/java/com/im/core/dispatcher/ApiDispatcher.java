package com.im.core.dispatcher;

import com.im.api.ApiInterceptor;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
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

    public ApiDispatcher() {
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
        RequestHandler handler = handlerMap.get(operation);

        if (handler == null) {
            log.warn("No handler for operation: {}", operation);
            request.responseWriter().writeError(ImErrorCode.NOT_FOUND, "no handler for: " + operation);
            return;
        }

        Span span = TRACER.spanBuilder(operation).startSpan();
        try (Scope scope = span.makeCurrent()) {
            process(request, handler);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void process(ApiRequest request, RequestHandler handler) {
        int idx = 0;
        try {
            // ── preHandle 链 ──
            for (; idx < interceptors.size(); idx++) {
                ApiInterceptor interceptor = interceptors.get(idx);
                try {
                    if (!interceptor.preHandle(request)) {
                        log.debug("Interceptor '{}' blocked request op={}", interceptor.name(), request.operation());
                        afterCompleteReverse(request, idx, null);
                        request.responseWriter().writeError(ImErrorCode.FORBIDDEN,
                                "blocked by interceptor: " + interceptor.name());
                        return;
                    }
                } catch (ImException e) {
                    log.warn("Interceptor '{}' preHandle rejected: {} {}", interceptor.name(),
                            e.getErrorCode().getCode(), e.getDetail());
                    afterCompleteReverse(request, idx, e);
                    request.responseWriter().writeError(e.getErrorCode(), e.getDetail());
                    return;
                } catch (Exception e) {
                    log.warn("Interceptor '{}' preHandle threw", interceptor.name(), e);
                    afterCompleteReverse(request, idx, null);
                    request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR,
                            "interceptor error: " + interceptor.name());
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
                log.warn("Handler rejected: {} {} op={}", e.getCode(), e.getMessage(), request.operation());
                request.responseWriter().writeError(e.getErrorCode(), e.getDetail());
            } catch (Exception e) {
                handlerEx = e;
                log.error("Handler error: op={}", request.operation(), e);
                request.responseWriter().writeError(ImErrorCode.INTERNAL_ERROR, e.getMessage());
            } finally {
                afterCompleteReverse(request, idx, handlerEx);
            }

            // handler 正常返回且未自行写响应时，由 ResponseWriter 自动序列化
            if (handlerEx == null) {
                request.responseWriter().write(result);
            }
        } finally {
            // 最外层保障，防止拦截器抛意外异常
        }
    }

    /** 反序回调已通过的拦截器的 afterCompletion */
    private void afterCompleteReverse(ApiRequest request, int passedCount, Exception error) {
        for (int i = passedCount - 1; i >= 0; i--) {
            try {
                interceptors.get(i).afterCompletion(request,
                        error == null ? "proceed" : null, error);
            } catch (Exception e) {
                log.warn("Interceptor '{}' afterCompletion threw: {}", interceptors.get(i).name(), e.getMessage());
            }
        }
    }
}
