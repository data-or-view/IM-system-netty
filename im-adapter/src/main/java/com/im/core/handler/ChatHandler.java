package com.im.core.handler;

import com.im.api.*;
import com.im.api.content.IMessageContent;
import com.im.core.handler.ContentParser;
import com.im.core.handler.WebhookService;
import com.im.core.usecase.SendMessageUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class ChatHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    public static final String CONTENT_TYPE_HEADER = "_ct";
    public static final String MSG_SEQ_HEADER = "_ms";

    private final SendMessageUseCase sendMessageUseCase;

    public ChatHandler(SendMessageUseCase sendMessageUseCase) {
        this.sendMessageUseCase = sendMessageUseCase;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        SendMessageUseCase.SendMessageResult result = sendMessageUseCase.execute(msg);
        if (result == null) {
            sendError(ctx, msg, "message processing failed");
            return;
        }

        IMCommand ack = msg.createAcknowledgement(result.responseType());
        ack.putHeader("status", "RECEIVED");
        if (result.conversationId() != null) {
            ack.putHeader("conversationId", result.conversationId());
            ack.putHeader(MSG_SEQ_HEADER, String.valueOf(result.seq()));
        }
        ctx.writeAndFlush(ack);
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.SINGLE_CHAT, CommandType.GROUP_CHAT);
    }
}
