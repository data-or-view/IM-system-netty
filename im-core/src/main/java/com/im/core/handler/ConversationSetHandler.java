package com.im.core.handler;

import com.im.api.*;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 会话设置更新处理器。
 *
 * 请求（CONVERSATION_SET）：
 *   HEADERS: { "conversationId": "...", "_pin": "true", "_mute": "1" }
 *     _pin:  布尔值字符串 "true"/"false"
 *     _mute: "0"=正常, "1"=不提醒, "2"=不接收
 *
 * 响应（CONVERSATION_SET_ACK）：
 *   HEADERS: { "conversationId": "...", "status": "OK" }
 */
public class ConversationSetHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationSetHandler.class);

    private final IConversationManager conversationManager;

    public ConversationSetHandler(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("fromUserId");
        String conversationId = msg.getHeader("conversationId");

        if (userId == null || conversationId == null) {
            sendError(ctx, msg, "fromUserId and conversationId are required");
            return;
        }

        // 解析置顶
        String pinStr = msg.getHeader("_pin");
        if (pinStr != null) {
            boolean pinned = Boolean.parseBoolean(pinStr);
            conversationManager.setPinned(userId, conversationId, pinned);
        }

        // 解析免打扰
        String muteStr = msg.getHeader("_mute");
        if (muteStr != null) {
            try {
                int opt = Integer.parseInt(muteStr);
                if (opt >= Conversation.RECV_OPT_NORMAL
                        && opt <= Conversation.RECV_OPT_NOT_RECEIVE) {
                    conversationManager.setRecvMsgOpt(userId, conversationId, opt);
                } else {
                    sendError(ctx, msg, "invalid _mute value: " + muteStr);
                    return;
                }
            } catch (NumberFormatException e) {
                sendError(ctx, msg, "invalid _mute format: " + muteStr);
                return;
            }
        }

        // 回复 ACK
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
