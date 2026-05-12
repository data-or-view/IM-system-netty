package com.im.api;

import io.netty.channel.ChannelHandlerContext;

/**
 * 消息拦截器，参考 Spring MVC 的 HandlerInterceptor + RocketMQ 的 SendMessageHook。
 *
 * 执行时机（在 MessageRouterHandler 中）：
 * <pre>
 * for (IMInterceptor interceptor : interceptors) {
 *     if (!interceptor.preHandle(ctx, msg)) {
 *         return;  // 阻断，handler 不执行
 *     }
 * }
 * try {
 *     handler.handle(ctx, msg);
 * } finally {
 *     for (int i = interceptors.size() - 1; i >= 0; i--) {
 *         interceptors.get(i).afterComplete(ctx, msg, ex);
 *     }
 * }
 * </pre>
 *
 * 典型用途：
 *   ┌──────────────┬───────────────────┬────────────────┐
 *   │ 场景         │ preHandle         │ afterComplete  │
 *   ├──────────────┼───────────────────┼────────────────┤
 *   │ 鉴权         │ 检查 token → 阻断 │ —              │
 *   │ 限流         │ 令牌桶 → 返回 429 │ 归还令牌       │
 *   │ 审计日志     │ 记录请求入参      │ 记录结果/耗时  │
 *   │ TraceId 注入 │ 注入 traceId 头   │ 上报链路       │
 *   └──────────────┴───────────────────┴────────────────┘
 */
public interface IMInterceptor {

    /** 拦截器名称（用于日志和排序） */
    String name();

    /**
     * 排序优先级（数值越小越先执行）。
     * <p>
     * 参考 Spring 的 {@code Ordered} 接口语义：
     * <pre>
     * Integer.MIN_VALUE → 最先执行（如鉴权）
     * 0                → 默认
     * Integer.MAX_VALUE → 最后执行（如审计日志）
     * </pre>
     *
     * @return 排序值，默认 0
     */
    default int order() {
        return 0;
    }

    /**
     * 前置拦截（handler 执行前调用）。
     *
     * @param ctx ChannelHandlerContext
     * @param msg 解码后的 IMCommand
     * @return true → 继续执行；false → 阻断，handler 不执行
     */
    boolean preHandle(ChannelHandlerContext ctx, IMCommand msg);

    /**
     * 完成后回调（handler 执行后调用，在 finally 块中执行）。
     * 无论 handler 是否抛异常都会触发。
     *
     * ⚠️ 不要在 {@code afterComplete} 中抛异常——它运行在 finally 块中，
     *    异常会覆盖原始异常。如有必要需自行 try-catch。
     *
     * @param ctx  ChannelHandlerContext
     * @param msg  原始消息
     * @param ex   handler 抛出的异常（null 表示正常完成）
     */
    void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex);
}
