package com.im.bootstrap.http;

import com.im.api.ImException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * HTTP REST 路由分发器 + 拦截器链。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>维护路由表（静态路由 HashMap + 参数化路由 List）</li>
 *   <li>解析入站 {@link FullHttpRequest} 并分派到对应 handler</li>
 *   <li>执行 {@link HttpInterceptor} 链（preHandle → handler → afterComplete）</li>
 *   <li>全局异常处理（{@link ImException → HTTP 错误码}）</li>
 *   <li>处理 CORS 预检请求</li>
 * </ul>
 *
 * <p>业务 handler 在独立的域控制器中注册，例如：</p>
 * <pre>{@code
 *   HttpRestHandler router = new HttpRestHandler();
 *   new UserRestHandler(userManager).register(router);
 *   router.addInterceptor(new HttpRequestLogInterceptor());
 * }</pre>
 *
 * <p>参数化路径示例：{@code router.get("/api/user/{userId}", handler)}。
 * 多个动态路由匹配时优先返回最先注册的。</p>
 */
@ChannelHandler.Sharable
public class HttpRestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRestHandler.class);

    /** 静态路由表（精确匹配，O(1)） */
    private final Map<String, BiFunction<FullHttpRequest, ChannelHandlerContext, Object>> staticRoutes = new HashMap<>();

    /** 参数化路由表（模式匹配，遍历） */
    private final List<RouteEntry> dynamicRoutes = new ArrayList<>();

    /** 拦截器链（按 order() 排序） */
    private final List<HttpInterceptor> interceptors = new ArrayList<>();

    public HttpRestHandler() {
        get("/api/health", (req, ctx) -> Map.of("status", "ok"));
    }

    // ── 路由注册 ──

    /**
     * 注册 POST 路由。
     *
     * @param path    API 路径，如 /api/user/register 或 /api/user/{userId}
     * @param handler 处理函数，返回 Object 将自动序列化为 JSON 响应
     */
    public void post(String path, BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler) {
        addRoute("POST", path, handler);
    }

    /**
     * 注册 GET 路由。
     *
     * @param path    API 路径，如 /api/user/info 或 /api/user/{userId}
     * @param handler 处理函数，返回 Object 将自动序列化为 JSON 响应
     */
    public void get(String path, BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler) {
        addRoute("GET", path, handler);
    }

    private void addRoute(String method, String path, BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler) {
        PathPattern pattern = new PathPattern(path);
        if (pattern.isStatic()) {
            staticRoutes.put(method + ":" + path, handler);
        } else {
            dynamicRoutes.add(new RouteEntry(method, pattern, handler));
        }
    }

    // ── 拦截器注册 ──

    /**
     * 注册拦截器。
     * <p>拦截器按 {@link HttpInterceptor#order()} 升序排序。</p>
     */
    public void addInterceptor(HttpInterceptor interceptor) {
        interceptors.add(interceptor);
        interceptors.sort(Comparator.comparingInt(HttpInterceptor::order));
        log.info("HTTP interceptor registered: {} (order={})", interceptor.name(), interceptor.order());
    }

    // ── 请求分发 ──

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() == HttpMethod.OPTIONS) {
            JsonResponse.ok(ctx, Map.of());
            return;
        }

        String uri = req.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }

        String method = req.method().name();

        // 1. 查找 handler（先静态、再动态）
        BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler = findHandler(method, path);
        if (handler == null) {
            JsonResponse.notFound(ctx, "no route: " + req.method() + " " + path);
            return;
        }

        // 2. 拦截器链 + handler 执行
        executeWithChain(ctx, req, handler);
    }

    private BiFunction<FullHttpRequest, ChannelHandlerContext, Object> findHandler(String method, String path) {
        // 静态路由 O(1) 查找
        String exactKey = method + ":" + path;
        BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler = staticRoutes.get(exactKey);
        if (handler != null) return handler;

        // 动态路由遍历匹配
        for (RouteEntry entry : dynamicRoutes) {
            if (!entry.method.equals(method)) continue;
            if (entry.pattern.match(path).matches()) {
                return entry.handler;
            }
        }
        return null;
    }

    // ── 拦截器链执行 ──

    private void executeWithChain(ChannelHandlerContext ctx, FullHttpRequest req,
                                  BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler) {
        int idx = 0;
        try {
            // preHandle 链（按 order 升序）
            for (; idx < interceptors.size(); idx++) {
                HttpInterceptor interceptor = interceptors.get(idx);
                if (!interceptor.preHandle(req, ctx)) {
                    // 被阻断：已通过的拦截器反向执行 afterComplete
                    for (int j = idx - 1; j >= 0; j--) {
                        afterCompleteSafe(interceptors.get(j), req, ctx, null, null);
                    }
                    return;
                }
            }

            // 业务 handler 执行
            Exception handlerEx = null;
            Object result = null;
            try {
                result = handler.apply(req, ctx);
            } catch (ImException e) {
                handlerEx = e;
                log.warn("API error: {} {} - {}", req.method(), req.uri(), e.getDetail());
                JsonResponse.error(ctx, HttpResponseStatus.valueOf(e.getErrorCode().getCode()), e.getDetail());
            } catch (Exception e) {
                handlerEx = e;
                log.error("API error: {} {}", req.method(), req.uri(), e);
                JsonResponse.serverError(ctx, e.getMessage() != null ? e.getMessage() : "internal error");
            } finally {
                // afterComplete 链（反向执行）
                for (int i = idx - 1; i >= 0; i--) {
                    afterCompleteSafe(interceptors.get(i), req, ctx, result, handlerEx);
                }
            }

            // handler 正常返回且未自行写响应时，自动序列化
            if (handlerEx == null && result != null) {
                JsonResponse.ok(ctx, result);
            }
        } finally {
            // 最外层保障
        }
    }

    private void afterCompleteSafe(HttpInterceptor interceptor, FullHttpRequest req,
                                   ChannelHandlerContext ctx, Object result, Exception ex) {
        try {
            interceptor.afterComplete(req, ctx, result, ex);
        } catch (Exception e) {
            log.warn("Interceptor '{}' afterComplete threw: {}", interceptor.name(), e.getMessage());
        }
    }

    // ── 内部类型 ──

    private record RouteEntry(String method, PathPattern pattern,
                              BiFunction<FullHttpRequest, ChannelHandlerContext, Object> handler) {}
}
