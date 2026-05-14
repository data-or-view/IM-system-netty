package com.im.bootstrap.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMCommand;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * WebSocket JSON 编解码器。
 *
 * <p>替代 WebSocketIMDecoder + ByteBufToWebSocketHandler + IMEncoder。
 * 入站：TextWebSocketFrame → JSON → IMCommand
 * 出站：IMCommand → JSON → TextWebSocketFrame</p>
 *
 * <p>JSON 格式复用 {@link IMCommand#toJsonMap()} / {@link IMCommand#fromJsonMap(Map)}，
 * body 字段用 Base64 编码。</p>
 */
public class JsonWsCodec extends MessageToMessageCodec<WebSocketFrame, IMCommand> {

    private static final Logger log = LoggerFactory.getLogger(JsonWsCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BODY_KEY = "_body";

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.fireChannelRead(frame);
            return;
        }

        try {
            String text = textFrame.text();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(text, Map.class);

            // 提取并移除 _body（避免被 IMCommand.fromJsonMap 当作 header 处理）
            Object bodyVal = map.remove(BODY_KEY);
            IMCommand cmd = IMCommand.fromJsonMap(map);

            if (bodyVal instanceof String b64) {
                cmd.setBody(Base64.getDecoder().decode(b64));
            }

            out.add(cmd);
        } catch (Exception e) {
            log.warn("Failed to decode WS JSON frame: {}", e.getMessage());
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, IMCommand msg, List<Object> out) {
        try {
            Map<String, Object> map = msg.toJsonMap();
            if (msg.getBody() != null && msg.getBody().length > 0) {
                map.put(BODY_KEY, Base64.getEncoder().encodeToString(msg.getBody()));
            }
            String json = MAPPER.writeValueAsString(map);
            out.add(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.warn("Failed to encode WS JSON frame: {}", e.getMessage());
        }
    }
}
