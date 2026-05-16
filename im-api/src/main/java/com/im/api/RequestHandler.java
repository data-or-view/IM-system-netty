package com.im.api;

/**
 * 协议无关的业务处理器（类似 Spring MVC 的 {@code @RequestMapping} 方法）。
 *
 * <p>WS 和 HTTP 的请求在 Adapter 层统一转为 {@link ApiRequest}，
 * 由 {@code ApiDispatcher} 根据 operation 字符串分发到此接口的实现。</p>
 *
 * <p>一个实现类可以处理多个 operation，在 {@link #handle(ApiRequest)} 中判断
 * {@code request.operation()} 做不同处理。</p>
 *
 * <p>实现在 {@code ApiDispatcher.registerHandler(Operation, RequestHandler)} 中注册。</p>
 *
 * <p>实现示例：</p>
 * <pre>
 * class UserHandler implements RequestHandler {
 *     public Object handle(ApiRequest req) {
 *         return switch (req.operation()) {
 *             case "user.search" -> handleSearch(req);
 *             case "user.info" -> handleInfo(req);
 *             default -> throw new ImException(NOT_FOUND, "unsupported");
 *         };
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * 执行业务逻辑。
     *
     * @param request 协议无关的请求对象，包含业务参数和上下文
     * @return 业务结果，由 {@link ResponseWriter} 序列化为协议特定格式
     * @throws Exception 业务异常（由拦截器链负责捕获并转为错误响应）
     */
    Object handle(ApiRequest request) throws Exception;
}
