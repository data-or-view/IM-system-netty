package com.im.core.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.CommandType;
import com.im.api.Conversation;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.core.usecase.ConversationGetUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public class ConversationGetHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationGetHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationGetUseCase conversationGetUseCase;

    public ConversationGetHandler(ConversationGetUseCase conversationGetUseCase) {
        this.conversationGetUseCase = conversationGetUseCase;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("_uid");
        if (userId == null || userId.isBlank()) {
            sendError(ctx, msg, "fromUserId is required");
            return;
        }

        List<Conversation> conversations = conversationGetUseCase.execute(userId);

        IMCommand ack = msg.createAcknowledgement(CommandType.CONVERSATION_GET_ACK);
        ack.putHeader("_count", String.valueOf(conversations.size()));

        try {
            ack.setBody(MAPPER.writeValueAsBytes(conversations));
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
