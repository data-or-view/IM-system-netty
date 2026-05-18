package com.im.bootstrap.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.Operation;
import com.im.api.ResponseWriter;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.serialization.jackson.ObjectMapperProvider;
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

    public WsRequestAdapter(ApiDispatcher dispatcher, ExecutorService virtualExecutor) {
        this.dispatcher = dispatcher;
        this.virtualExecutor = virtualExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.fireChannelRead(frame);
            return;
        }

        String text = textFrame.text();
        if (text == null || text.isBlank()) {
            log.warn("Empty WS frame from {}", ctx.channel().remoteAddress());
            return;
        }

        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Invalid WS JSON from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
            return;
        }

        // 提取操作名 → 解析为 Operation 枚举
        String opStr = raw.containsKey("op") ? raw.get("op").toString() : null;
        if (opStr == null || opStr.isBlank()) {
            log.warn("Missing 'op' in WS frame from {}", ctx.channel().remoteAddress());
            return;
        }
        Operation operation = Operation.fromOpName(opStr);
        if (operation == null) {
            log.warn("Unknown operation '{}' from {}", opStr, ctx.channel().remoteAddress());
            return;
        }

        // 提取序列号
        int seq = 0;
        Object seqObj = raw.get("seq");
        if (seqObj instanceof Number) {
            seq = ((Number) seqObj).intValue();
        }

        // 提取业务参数（移除 op/seq 后剩余字段）
        Map<String, Object> params = new HashMap<>(raw);
        params.remove("op");
        params.remove("seq");

        // 提取协议头部（Authorization 等）
        Map<String, String> headers = new HashMap<>();
        if (params.containsKey("Authorization")) {
            headers.put("Authorization", params.get("Authorization").toString());
            params.remove("Authorization");
        }

        // 创建 ResponseWriter + ApiRequest 并提交到虚拟线程
        ResponseWriter responseWriter = new WsResponseWriter(ctx, seq, operation.opName());
        ApiRequest request = new ApiRequest(operation, params, headers, responseWriter, null);
        request.setAttribute("_channel", ctx.channel());
        virtualExecutor.execute(() -> dispatcher.dispatch(request));
    }
}
