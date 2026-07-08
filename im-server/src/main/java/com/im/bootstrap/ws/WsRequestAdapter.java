package com.im.bootstrap.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.ImHeaders;
import com.im.api.Operation;
import com.im.api.ProtocolFields;
import com.im.api.ResponseWriter;
import com.im.bootstrap.ClientIpResolver;
import com.im.bootstrap.DispatchSubmitter;
import com.im.bootstrap.RequestAdmission;
import com.im.common.trace.RequestIds;
import com.im.common.trace.TraceIds;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.core.session.NettyConnectionRef;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * WebSocket 请求适配器。
 *
 * <p>在 EventLoop 线程中将 WS 帧解码为 {@link ApiRequest}，
 * 提交到虚拟线程池由 {@link ApiDispatcher} 处理。</p>
 *
 * <p>替换 {@code JsonWsCodec + MessageRouterHandler} 组合。</p>
 *
 * <p>入站 WS 帧格式：</p>
 * <pre>
 * {
 *   "op": "user.search",
 *   "seq": 12345,
 *   "keyword": "abc",
 *   "limit": 10
 * }
 * </pre>
 */
@ChannelHandler.Sharable
public class WsRequestAdapter extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WsRequestAdapter.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ApiDispatcher dispatcher;
    private final ExecutorService virtualExecutor;
    private final RequestAdmission requestAdmission;
    private final String nodeId;

    public WsRequestAdapter(ApiDispatcher dispatcher, ExecutorService virtualExecutor) {
        this(dispatcher, virtualExecutor, null);
    }

    public WsRequestAdapter(ApiDispatcher dispatcher,
                            ExecutorService virtualExecutor,
                            RequestAdmission requestAdmission) {
        this(dispatcher, virtualExecutor, requestAdmission, null);
    }

    public WsRequestAdapter(ApiDispatcher dispatcher,
                            ExecutorService virtualExecutor,
                            RequestAdmission requestAdmission,
                            String nodeId) {
        this.dispatcher = dispatcher;
        this.virtualExecutor = virtualExecutor;
        this.requestAdmission = requestAdmission;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        String connectionId = NettyConnectionRef.connectionId(ctx.channel());
        String clientIp = ClientIpResolver.fromRemoteAddress(ctx.channel().remoteAddress());
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.fireChannelRead(frame);
            return;
        }

        String text = textFrame.text();
        if (text == null || text.isBlank()) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, RequestIds.next(),
                    LogFields.TRACE_ID, TraceIds.next(),
                    LogFields.PROTOCOL, "ws",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.CONNECTION_ID, connectionId,
                    LogFields.REASON, "empty_frame"));
            return;
        }

        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, RequestIds.next(),
                    LogFields.TRACE_ID, TraceIds.next(),
                    LogFields.PROTOCOL, "ws",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.CONNECTION_ID, connectionId,
                    LogFields.REASON, "invalid_json",
                    LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()));
            WsResponseWriter.writeProtocolError(ctx, "invalid json: " + e.getMessage());
            return;
        }
        Object requestIdObj = raw.get(ProtocolFields.CLIENT_REQUEST_ID);
        Object traceIdObj = raw.get(ProtocolFields.CLIENT_TRACE_ID);
        String requestId = requestId(requestIdObj);
        String traceId = traceId(traceIdObj);

        // 提取操作名 → 解析为 Operation 枚举
        String opStr = raw.containsKey(ProtocolFields.OP) ? raw.get(ProtocolFields.OP).toString() : null;
        if (opStr == null || opStr.isBlank()) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, requestId,
                    LogFields.TRACE_ID, traceId,
                    LogFields.PROTOCOL, "ws",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.CONNECTION_ID, connectionId,
                    LogFields.REASON, "missing_op"));
            WsResponseWriter.writeProtocolError(ctx, "missing 'op' field");
            return;
        }
        Operation operation = Operation.fromOpName(opStr);
        if (operation == null) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, requestId,
                    LogFields.TRACE_ID, traceId,
                    LogFields.OPERATION, opStr,
                    LogFields.PROTOCOL, "ws",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.CONNECTION_ID, connectionId,
                    LogFields.REASON, "unknown_operation"));
            WsResponseWriter.writeProtocolError(ctx, "unknown operation: " + opStr);
            return;
        }

        // 提取序列号
        int seq = 0;
        Object seqObj = raw.get(ProtocolFields.SEQ);
        if (seqObj instanceof Number) {
            seq = ((Number) seqObj).intValue();
        }

        if (!operation.supportsWebSocket()) {
            log.warn(StructuredLog.event(LogEvents.REQUEST_REJECTED,
                    LogFields.NODE_ID, nodeId,
                    LogFields.REQUEST_ID, requestId,
                    LogFields.TRACE_ID, traceId,
                    LogFields.OPERATION, operation.opName(),
                    LogFields.PROTOCOL, "ws",
                    LogFields.CLIENT_IP, clientIp,
                    LogFields.CONNECTION_ID, connectionId,
                    LogFields.WS_SEQ, seq,
                    LogFields.REASON, "operation_not_ws"));
            new WsResponseWriter(ctx, seq, operation.opName(), requestId)
                    .writeError(com.im.common.enums.ImErrorCode.BAD_REQUEST,
                            "operation only supports HTTP: " + operation.opName());
            return;
        }

        // 提取业务参数（移除 op/seq 后剩余字段）
        Map<String, Object> params = new HashMap<>(raw);
        params.remove(ProtocolFields.OP);
        params.remove(ProtocolFields.SEQ);
        params.remove(ProtocolFields.CLIENT_REQUEST_ID);
        params.remove(ProtocolFields.CLIENT_TRACE_ID);

        // 提取协议头部（Authorization 等）
        Map<String, String> headers = new HashMap<>();
        if (params.containsKey(ImHeaders.AUTHORIZATION)) {
            headers.put(ImHeaders.AUTHORIZATION, params.get(ImHeaders.AUTHORIZATION).toString());
            params.remove(ImHeaders.AUTHORIZATION);
        }
        headers.put(ImHeaders.REQUEST_ID, requestId);
        headers.put(ImHeaders.TRACE_ID, traceId);

        // 创建 ResponseWriter + ApiRequest 并提交到虚拟线程
        ResponseWriter responseWriter = new WsResponseWriter(ctx, seq, operation.opName(), requestId);
        ApiRequest request = new ApiRequest(operation, params, headers, responseWriter, null);
        request.setAttribute(ApiRequest.ATTR_CONNECTION_ID, connectionId);
        request.setAttribute(ApiRequest.ATTR_CLIENT_IP, clientIp);
        request.setAttribute(ApiRequest.ATTR_REQUEST_ID, requestId);
        request.setAttribute(ApiRequest.ATTR_TRACE_ID, traceId);
        request.setAttribute(ApiRequest.ATTR_WS_SEQ, seq);
        request.setAttribute(ApiRequest.ATTR_PROTOCOL, "ws");
        request.setAttribute(ApiRequest.ATTR_NODE_ID, nodeId);
        if (requestAdmission == null) {
            DispatchSubmitter.submit(dispatcher, virtualExecutor, request, log);
        } else {
            DispatchSubmitter.submit(dispatcher, virtualExecutor, requestAdmission, request, log);
        }
    }

    private static String requestId(Object value) {
        String requestId = RequestIds.firstNonBlank(value != null ? value.toString() : null);
        return requestId != null ? requestId : RequestIds.next();
    }

    private static String traceId(Object value) {
        String traceId = TraceIds.firstValid(value != null ? value.toString() : null);
        return traceId != null ? traceId : TraceIds.next();
    }
}
