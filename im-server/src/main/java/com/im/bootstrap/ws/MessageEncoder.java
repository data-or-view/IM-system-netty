package com.im.bootstrap.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
import com.im.api.ProtocolFields;
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
            data.put(ProtocolFields.MESSAGE_ID, msg.getMessageId());
            data.put(ProtocolFields.SEQUENCE_ID, msg.getSequenceId());
            data.put(ProtocolFields.TIMESTAMP, msg.getTimestamp());
            data.put(ProtocolFields.FROM_USER_ID, msg.getFromUserId());
            data.put(ProtocolFields.TO_USER_ID, msg.getToUserId());
            data.put(ProtocolFields.GROUP_ID, msg.getGroupId());
            data.put(ProtocolFields.CONVERSATION_ID, msg.getConversationId());
            data.put(ProtocolFields.CONTENT_TYPE, msg.getContentType());
            data.put(ProtocolFields.CONTENT, msg.getContent());
            data.put(ProtocolFields.MESSAGE_SEQ, msg.getMessageSeq());
            data.put(ProtocolFields.STATUS, msg.getStatus());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(ProtocolFields.OP, ProtocolFields.OP_MESSAGE);
            envelope.put(ProtocolFields.DATA, data);

            String json = MAPPER.writeValueAsString(envelope);
            out.add(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("Failed to encode Message to WS frame: msgId={}", msg.getMessageId(), e);
        }
    }
}
