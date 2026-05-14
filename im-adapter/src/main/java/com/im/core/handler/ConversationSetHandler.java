package com.im.core.handler;

import com.im.api.Conversation;
import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.core.usecase.ConversationSetUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class ConversationSetHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationSetHandler.class);

    private final ConversationSetUseCase conversationSetUseCase;

    public ConversationSetHandler(ConversationSetUseCase conversationSetUseCase) {
        this.conversationSetUseCase = conversationSetUseCase;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("_uid");
        String conversationId = msg.getHeader("conversationId");

        if (userId == null || conversationId == null) {
            sendError(ctx, msg, "_uid and conversationId are required");
            return;
        }

        String pinStr = msg.getHeader("_pin");
        if (pinStr != null) {
            boolean pinned = Boolean.parseBoolean(pinStr);
            conversationSetUseCase.setPinned(userId, conversationId, pinned);
        }

        String muteStr = msg.getHeader("_mute");
        if (muteStr != null) {
            try {
                int opt = Integer.parseInt(muteStr);
                if (opt >= Conversation.RECV_OPT_NORMAL && opt <= Conversation.RECV_OPT_NOT_RECEIVE) {
                    conversationSetUseCase.setRecvMsgOpt(userId, conversationId, opt);
                } else {
                    sendError(ctx, msg, "invalid _mute value: " + muteStr);
                    return;
                }
            } catch (NumberFormatException e) {
                sendError(ctx, msg, "invalid _mute format: " + muteStr);
                return;
            }
        }

        IMCommand ack = msg.createAcknowledgement(CommandType.CONVERSATION_SET_ACK);
        ack.putHeader("conversationId", conversationId);
        ack.putHeader("status", "OK");
        ctx.writeAndFlush(ack);

        log.info("Conversation settings updated: userId={}, conv={}", userId, conversationId);
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.CONVERSATION_SET);
    }
}
