package com.im.bootstrap.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ResponseWriter;
import com.im.common.enums.ImErrorCode;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket 协议响应写回。
 *
 * <p>将 handler 返回的结果包装为 WS 帧：</p>
 * <ul>
 *   <li>正常: {@code {"op":"xxx_ack","seq":N,"code":0,"data":...}}</li>
 *   <li>错误: {@code {"op":"xxx_ack","seq":N,"code":ERROR_CODE,"msg":"..."}}</li>
 * </ul>
 */
public class WsResponseWriter implements ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(WsResponseWriter.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final ChannelHandlerContext ctx;
    private final int seq;
    private final String operation;
    private final AtomicBoolean committed = new AtomicBoolean(false);

    public WsResponseWriter(ChannelHandlerContext ctx, int seq, String operation) {
        this.ctx = ctx;
        this.seq = seq;
        this.operation = operation;
    }

    @Override
    public void write(Object result) {
        if (!committed.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("op", operation + "_ack");
        envelope.put("seq", seq);
        envelope.put("code", 0);
        if (result != null) {
            envelope.put("data", result);
        }
        writeFrame(envelope);
    }

    @Override
    public void writeError(ImErrorCode code, String detail) {
        if (!committed.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("op", operation + "_ack");
        envelope.put("seq", seq);
        envelope.put("code", code.getCode());
        envelope.put("msg", code.getMessage());
        if (detail != null && !detail.isEmpty()) {
            envelope.put("detail", detail);
        }
        writeFrame(envelope);
    }

    @Override
    public boolean isCommitted() {
        return committed.get();
    }

    /**
     * 写协议层错误帧（用于 JSON 解析失败、op 缺失等无法构建正常 ack 的场景）。
     * 使用固定 {@code "error"} 作为 op，不包含 seq。
     */
    public static void writeProtocolError(ChannelHandlerContext ctx, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("op", "error");
        envelope.put("code", 400);
        envelope.put("msg", message);
        writeRawFrame(ctx, envelope);
    }

    private void writeFrame(Map<String, Object> envelope) {
        writeRawFrame(ctx, envelope);
    }

    private static void writeRawFrame(ChannelHandlerContext ctx, Map<String, Object> envelope) {
        try {
            String json = MAPPER.writeValueAsString(envelope);
            ctx.channel().writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("Failed to write WS response", e);
        }
    }
}
