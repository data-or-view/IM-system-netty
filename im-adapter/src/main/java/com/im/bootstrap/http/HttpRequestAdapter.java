package com.im.bootstrap.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * HTTP REST 请求适配器。
 *
 * <p>在 EventLoop 线程中将 HTTP 请求解码为 {@link ApiRequest}，
 * 提交到虚拟线程池由 {@link ApiDispatcher} 处理。</p>
 *
 * <p>替换 {@code HttpRestHandler} 的路由分发 + 拦截器链职责。</p>
 *
 * <p>CORS 预检请求（OPTIONS）直接回复，不进入分发。</p>
 */
@ChannelHandler.Sharable
public class HttpRequestAdapter extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestAdapter.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ApiDispatcher dispatcher;
    private final ExecutorService virtualExecutor;

    public HttpRequestAdapter(ApiDispatcher dispatcher, ExecutorService virtualExecutor) {
        this.dispatcher = dispatcher;
        this.virtualExecutor = virtualExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        // CORS 预检
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

        // 查找 Operation（替换 OperationMapping）
        Operation operation = Operation.fromHttp(method, path);
        if (operation == null) {
            log.warn("No route: {} {}", method, path);
            JsonResponse.notFound(ctx, "no route: " + method + " " + path);
            return;
        }

        // 解析参数
        Map<String, Object> params = new HashMap<>();

        // Query string 参数
        int qIdx = uri.indexOf('?');
        if (qIdx >= 0) {
            String query = uri.substring(qIdx + 1);
            for (String pair : query.split("&")) {
                int eIdx = pair.indexOf('=');
                if (eIdx > 0) {
                    String key = decodeURI(pair.substring(0, eIdx));
                    String val = decodeURI(pair.substring(eIdx + 1));
                    params.put(key, val);
                }
            }
        }

        // Body 参数（JSON + multipart）
        byte[] bodyRaw = null;
        if (req.content().readableBytes() > 0) {
            ByteBuf buf = req.content();
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);

            if (operation.equals("file.upload")) {
                // 文件上传：保留原始 body，由 handler 自行解析 multipart
                bodyRaw = bytes;
            } else {
                // JSON body → 合并到 params
                String contentType = req.headers().get("Content-Type", "");
                if (contentType.contains("application/json")) {
                    try {
                        Map<String, Object> bodyMap = MAPPER.readValue(bytes, MAP_TYPE);
                        params.putAll(bodyMap);
                    } catch (Exception e) {
                        log.warn("Invalid JSON body for {} {}: {}", method, path, e.getMessage());
                    }
                }
            }
        }

        // 协议头部
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", req.headers().get("Content-Type", ""));
        String auth = req.headers().get("Authorization");
        if (auth != null) {
            headers.put("Authorization", auth);
        }

        // 创建 ResponseWriter + ApiRequest 并提交到虚拟线程
        ResponseWriter responseWriter = new HttpResponseWriter(ctx);
        ApiRequest request = new ApiRequest(operation, params, headers, responseWriter, bodyRaw);
        virtualExecutor.execute(() -> dispatcher.dispatch(request));
    }

    private static String decodeURI(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
