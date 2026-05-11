package com.im.core.handler;

import com.im.api.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 会话列表获取处理器。
 *
 * 请求（CONVERSATION_GET）：
 *   无需额外 headers（fromUserId 由 AuthenticationInterceptor 注入）
 *
 * 响应（CONVERSATION_GET_ACK）：
 *   HEADERS: { "_count": "3" }
 *   BODY:    JSON array of Conversation objects
 */
public class ConversationGetHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationGetHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IConversationManager conversationManager;

    public ConversationGetHandler(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("fromUserId");
        if (userId == null || userId.isBlank()) {
            sendError(ctx, msg, "fromUserId is required");
            return;
        }

        List<Conversation> conversations = conversationManager.getConversations(userId);

        IMCommand ack = msg.createAcknowledgement(CommandType.CONVERSATION_GET_ACK);
        ack.putHeader("_count", String.valueOf(conversations.size()));

        try {
            byte[] body = MAPPER.writeValueAsBytes(conversations);
            ack.setBody(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize conversations", e);
            ack.setBody("[]".getBytes(StandardCharsets.UTF_8));
        }

        ctx.writeAndFlush(ack);
        log.info("Returned {} conversations for user {}", conversations.size(), userId);
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.CONVERSATION_GET);
    }
}
