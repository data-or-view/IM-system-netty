package com.im.bootstrap.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将服务端推送的 {@link Message} 编码为 WebSocket 文本帧。
 *
 * <p>出站格式：
 * <pre>
 * {
 *   "op": "message",
 *   "data": {
 *     "messageId": "...",
 *     "sequenceId": 123,
 *     "timestamp": 1700000000000,
 *     "fromUserId": "caller",
 *     "toUserId": "callee",
 *     "conversationId": "...",
 *     "contentType": 5,
 *     "content": "{\"_act\":2,\"_room\":\"...\",\"_token\":\"...\"}",
 *     "messageSeq": 1,
 *     "status": 0
 *   }
 * }
 * </pre>
 * </p>
 */
@ChannelHandler.Sharable
public class MessageEncoder extends MessageToMessageEncoder<Message> {

    private static final Logger log = LoggerFactory.getLogger(MessageEncoder.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, List<Object> out) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", msg.getMessageId());
            data.put("sequenceId", msg.getSequenceId());
            data.put("timestamp", msg.getTimestamp());
            data.put("fromUserId", msg.getFromUserId());
            data.put("toUserId", msg.getToUserId());
            data.put("groupId", msg.getGroupId());
            data.put("conversationId", msg.getConversationId());
            data.put("contentType", msg.getContentType());
            data.put("content", msg.getContent());
            data.put("messageSeq", msg.getMessageSeq());
            data.put("status", msg.getStatus());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("op", "message");
            envelope.put("data", data);

            String json = MAPPER.writeValueAsString(envelope);
            out.add(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("Failed to encode Message to WS frame: msgId={}", msg.getMessageId(), e);
        }
    }
}
