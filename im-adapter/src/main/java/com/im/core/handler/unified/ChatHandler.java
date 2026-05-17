package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.RequestHandler;
import com.im.api.content.IMessageContent;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.handler.ContentParser;
import com.im.core.usecase.SendMessageUseCase;

import java.util.Map;

/**
 * 消息发送 handler：单聊 {@code chat.send}、群聊 {@code chat.send.group}。
 *
 * <p>注意：此 handler 处理的是"发送消息"，
 * 不是"拉取消息"（由 {@code MessageHandler} 处理）。</p>
 */
public class ChatHandler implements RequestHandler {

    private final SendMessageUseCase sendMessageUseCase;

    public ChatHandler(SendMessageUseCase sendMessageUseCase) {
        this.sendMessageUseCase = sendMessageUseCase;
    }

    @Override
    public Object handle(ApiRequest req) {
        String uid = req.currentUserId();
        if (uid == null) {
            throw new ImException(ImErrorCode.UNAUTHORIZED, "not authenticated");
        }
        String toUserId = req.getString("toUserId");
        String groupId = req.getString("groupId");

        // 解析消息内容
        IMessageContent content;
        try {
            content = ContentParser.parse(req.params(), req.bodyRaw());
        } catch (Exception e) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "invalid content: " + e.getMessage());
        }
        if (content == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "content type (_ct) is required");
        }

        SendMessageUseCase.SendMessageResult result = sendMessageUseCase.execute(
                req.params(), uid, toUserId, groupId, content);

        if (result == null) {
            throw new ImException(ImErrorCode.FORBIDDEN, "message sending blocked");
        }

        return Map.of("status", "RECEIVED",
                "conversationId", result.conversationId(),
                "seq", result.seq());
    }
}
