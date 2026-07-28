package com.im.bootstrap.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.ImHeaders;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.bootstrap.ClientIpResolver;
import com.im.bootstrap.DispatchSubmitter;
import com.im.bootstrap.RequestAdmission;
import com.im.bootstrap.health.HealthProbeHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.trace.RequestIds;
import com.im.common.trace.TraceIds;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
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
    private final RequestAdmission requestAdmission;
    private final HealthProbeHandler healthProbeHandler;
    private final String nodeId;
    private final boolean trustedProxyEnabled;
    private final String clientIpHeader;

    public HttpRequestAdapter(ApiDispatcher dispatcher, ExecutorService virtualExecutor) {
        this(dispatcher, virtualExecutor, null);
    }

    public HttpRequestAdapter(ApiDispatcher dispatcher,
                              ExecutorService virtualExecutor,
                              RequestAdmission requestAdmission) {
        this(dispatcher, virtualExecutor, requestAdmission, null);
    }

    public HttpRequestAdapter(ApiDispatcher dispatcher,
                              ExecutorService virtualExecutor,
                              RequestAdmission requestAdmission,
                              String nodeId) {
        this(dispatcher, virtualExecutor, requestAdmission, nodeId, false, ClientIpResolver.DEFAULT_PROXY_HEADER);
    }

    public HttpRequestAdapter(ApiDispatcher dispatcher,
                              ExecutorService virtualExecutor,
                              RequestAdmission requestAdmission,
                              String nodeId,
                              boolean trustedProxyEnabled,
                              String clientIpHeader) {
        this.dispatcher = dispatcher;
        this.virtualExecutor = virtualExecutor;
        this.requestAdmission = requestAdmission;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId;
        this.healthProbeHandler = new HealthProbeHandler(nodeId, requestAdmission);
        this.trustedProxyEnabled = trustedProxyEnabled;
        this.clientIpHeader = clientIpHeader;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String requestOrigin = req.headers().get(HttpHeaderNames.ORIGIN);
        // CORS 预检
        if (req.method() == HttpMethod.OPTIONS) {
            JsonResponse.ok(ctx, Map.of(), null, requestOrigin);
            return;
        }

        if (healthProbeHandler.handleIfHealthProbe(ctx, req)) {
            return;
        }

        String uri = req.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        String method = req.method().name();
        String requestId = RequestIds.firstNonBlank(req.headers().get(ImHeaders.REQUEST_ID));
        if (requestId == null) {
            requestId = RequestIds.next();
        }
        String traceId = TraceIds.firstValid(req.headers().get(ImHeaders.TRACE_ID));
        if (traceId == null) {
            traceId = TraceIds.fromTraceparent(req.headers().get(ImHeaders.TRACEPARENT));
        }
        if (traceId == null) {
            traceId = TraceIds.next();
        }
        String clientIp = ClientIpResolver.fromHttpRequest(
                req, ctx.channel().remoteAddress(), trustedProxyEnabled, clientIpHeader);

        // 查找 Operation（替换 OperationMapping）
        Operation operation = Operation.fromHttp(method, path);
        if (operation == null) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, requestId,
                    LogFields.TRACE_ID, traceId,
                    LogFields.PROTOCOL, "http",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.HTTP_METHOD, method,
                    LogFields.HTTP_PATH, path,
                    LogFields.ERROR_CODE, ImErrorCode.NOT_FOUND.getCode(),
                    LogFields.REASON, "no_route"));
            JsonResponse.error(ctx, HttpResponseStatus.NOT_FOUND, "no route: " + method + " " + path, requestId, requestOrigin);
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

            // Binary upload bodies are no longer accepted by the API gateway. Object storage
            // receives bytes directly through the exact-size POST policy returned by file.upload.sign.
            String contentType = req.headers().get(ImHeaders.CONTENT_TYPE, "");
            if (contentType.contains(ImHeaders.APPLICATION_JSON)) {
                try {
                    Map<String, Object> bodyMap = MAPPER.readValue(bytes, MAP_TYPE);
                    params.putAll(bodyMap);
                } catch (Exception e) {
                    log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                            LogFields.NODE_ID, nodeId,
                            LogFields.REQUEST_ID, requestId,
                            LogFields.TRACE_ID, traceId,
                            LogFields.OPERATION, operation.opName(),
                            LogFields.PROTOCOL, "http",
                            LogFields.CLIENT_IP, clientIp,
                            LogFields.HTTP_METHOD, method,
                            LogFields.HTTP_PATH, path,
                            LogFields.ERROR_CODE, ImErrorCode.BAD_REQUEST.getCode(),
                            LogFields.REASON, "invalid_json"));
                    JsonResponse.imError(ctx, ImErrorCode.BAD_REQUEST, "invalid json body", requestId, requestOrigin);
                    return;
                }
            }
        }

        // 协议头部
        Map<String, String> headers = new HashMap<>();
        headers.put(ImHeaders.CONTENT_TYPE, req.headers().get(ImHeaders.CONTENT_TYPE, ""));
        String auth = req.headers().get(ImHeaders.AUTHORIZATION);
        if (auth != null) {
            headers.put(ImHeaders.AUTHORIZATION, auth);
        }
        if (requestOrigin != null) {
            headers.put("Origin", requestOrigin);
        }
        headers.put(ImHeaders.REQUEST_ID, requestId);
        headers.put(ImHeaders.TRACE_ID, traceId);

        // 创建 ResponseWriter + ApiRequest 并提交到虚拟线程
        ResponseWriter responseWriter = new HttpResponseWriter(ctx, requestId);
        ApiRequest request = new ApiRequest(operation, params, headers, responseWriter, bodyRaw);
        request.setAttribute(ApiRequest.ATTR_REQUEST_ID, requestId);
        request.setAttribute(ApiRequest.ATTR_TRACE_ID, traceId);
        request.setAttribute(ApiRequest.ATTR_CLIENT_IP, clientIp);
        request.setAttribute(ApiRequest.ATTR_PROTOCOL, "http");
        request.setAttribute(ApiRequest.ATTR_NODE_ID, nodeId);
        request.setAttribute(ApiRequest.ATTR_HTTP_METHOD, method);
        request.setAttribute(ApiRequest.ATTR_HTTP_PATH, path);
        if (requestAdmission == null) {
            DispatchSubmitter.submit(dispatcher, virtualExecutor, request, log);
        } else {
            DispatchSubmitter.submit(dispatcher, virtualExecutor, requestAdmission, request, log);
        }
    }

    private static String decodeURI(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
