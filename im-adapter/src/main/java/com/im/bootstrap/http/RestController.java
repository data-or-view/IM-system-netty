package com.im.bootstrap.http;

/**
 * REST 控制器接口。
 *
 * <p>每个域控制器通过实现此接口并在 {@link #register(HttpRestHandler)} 中调用
 * {@code router.post()} / {@code router.get()} 注册路由。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   public class UserRestHandler implements RestController {
 *       public void register(HttpRestHandler router) {
 *           router.post("/api/user/register", this::handleRegister);
 *           router.get("/api/user/info", this::handleInfo);
 *       }
 *   }
 * }</pre>
 */
@FunctionalInterface
public interface RestController {

    /**
     * 向指定路由器注册当前控制器的所有路由。
     *
     * @param router HTTP 路由分发器
     */
    void register(HttpRestHandler router);
}
