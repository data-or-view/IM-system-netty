package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Conversation;
import com.im.api.IConversationManager;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;

import java.util.List;
import java.util.Map;

/**
 * 会话域 handler：查询列表、更新设置（置顶、免打扰）。
 *
 * <p>合并 WS {@code ConversationGetHandler/ConversationSetHandler} + HTTP {@code ConversationRestHandler}。</p>
 */
public class ConversationHandler implements RequestHandler {

    private final IConversationManager conversationManager;

    public ConversationHandler(IConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "conversation.list" -> handleList(req);
            case "conversation.set" -> handleSet(req);
            default -> throw new ImException(ImErrorCode.NOT_FOUND, "unsupported: " + req.operation());
        };
    }

    private Object handleList(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null) {
            // WS 路径从 attributes 取 userId（认证拦截器设置）
            userId = req.attribute("userId");
        }
        if (userId == null) throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        List<Conversation> conversations = conversationManager.getConversations(userId);
        return Map.of("userId", userId, "conversations", conversations, "count", conversations.size());
    }

    private Object handleSet(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null) userId = req.attribute("userId");
        String conversationId = req.getString("conversationId");
        if (userId == null || conversationId == null) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId and conversationId are required");
        }
        if (req.params().containsKey("pinned")) {
            conversationManager.setPinned(userId, conversationId, req.getBoolean("pinned", false));
        }
        if (req.params().containsKey("recvMsgOpt")) {
            conversationManager.setRecvMsgOpt(userId, conversationId, req.getInt("recvMsgOpt", 0));
        }
        return Map.of("conversationId", conversationId, "status", "OK");
    }
}
