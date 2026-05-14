package com.im.bootstrap.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * HTTP 请求拦截器，参考 Spring MVC 的 HandlerInterceptor + 项目已有的 {@code IMInterceptor}。
 *
 * <p>执行时机（在 {@link HttpRestHandler} 中）：</p>
 * <pre>{@code
 * for (HttpInterceptor interceptor : interceptors) {
 *     if (!interceptor.preHandle(req, ctx)) {
 *         return;  // 阻断，业务 handler 不执行
 *     }
 * }
 * try {
 *     handler.apply(req, ctx);
 * } finally {
 *     for (int i = interceptors.size() - 1; i >= 0; i--) {
 *         interceptors.get(i).afterComplete(req, ctx, result, ex);
 *     }
 * }
 * }</pre>
 *
 * <p>典型用途：</p>
 * <ul>
 *   <li>鉴权：检查 token → 阻断未认证请求</li>
 *   <li>请求日志：记录入参和耗时</li>
 *   <li>CORS 预检统一处理</li>
 * </ul>
 *
 * <p>排序规则：{@link #order()} 返回值越小越先执行。
 * 参考值：{@code Integer.MIN_VALUE} 鉴权，{@code 0} 默认，{@code Integer.MAX_VALUE} 日志。</p>
 */
public interface HttpInterceptor {

    /** 拦截器名称（用于日志和调试）。 */
    String name();

    /**
     * 排序优先级（数值越小越先执行）。
     * <p>默认 0，参考 Spring 的 {@code Ordered} 语义。</p>
     */
    default int order() {
        return 0;
    }

    /**
     * 前置拦截（业务 handler 执行前调用）。
     *
     * @param req HTTP 请求
     * @param ctx ChannelHandlerContext
     * @return true 继续执行；false 阻断，业务 handler 不执行
     */
    boolean preHandle(FullHttpRequest req, ChannelHandlerContext ctx);

    /**
     * 完成后回调（业务 handler 执行后调用，在 finally 块中执行）。
     * 无论业务 handler 是否抛异常都会触发。
     *
     * @param req    HTTP 请求
     * @param ctx    ChannelHandlerContext
     * @param result 业务 handler 返回值（异常时为 null）
     * @param ex     handler 抛出的异常（正常为 null）
     */
    void afterComplete(FullHttpRequest req, ChannelHandlerContext ctx, Object result, Exception ex);
}
