package com.im.api;

/**
 * 协议无关的请求拦截器（类似 Spring MVC 的 {@code HandlerInterceptor}）。
 *
 * <p>一次注册，WS 和 HTTP 同时生效。</p>
 *
 * <p>执行顺序：</p>
 * <pre>
 *   preHandle 按 order 升序执行
 *   handler 执行后，已通过的拦截器反序执行 afterCompletion
 *   任一 preHandle 返回 false / 抛异常 → 阻断，已通过的拦截器反序 afterCompletion
 * </pre>
 */
public interface ApiInterceptor {

    /** 拦截器名称（日志/调试用） */
    default String name() { return getClass().getSimpleName(); }

    /** 排序值，越小越优先 */
    default int order() { return 0; }

    /**
     * 前置处理。
     *
     * @param request 请求对象（可修改 attributes 传递上下文）
     * @return true 继续执行链，false 阻断
     */
    boolean preHandle(ApiRequest request);

    /**
     * 后置完成回调（在 finally 块中调用，无论正常或异常）。
     *
     * @param request 请求对象
     * @param result  handler 返回结果（正常时不为 null，异常时为 null）
     * @param error    handler 抛出的异常（正常时为 null）
     */
    default void afterCompletion(ApiRequest request, Object result, Exception error) {}
}
