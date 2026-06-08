package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.Conversation;
import com.im.api.IConversationAccessChecker;
import com.im.api.IConversationManager;
import com.im.api.RequestPreconditions;
import com.im.api.RequestHandler;
import com.im.common.exception.NotFoundException;
import com.im.common.validation.Preconditions;

import java.util.List;
import java.util.Map;

/**
 * 会话域 handler：查询列表、更新设置（置顶、免打扰）。
 *
 * <p>合并 WS {@code ConversationGetHandler/ConversationSetHandler} + HTTP {@code ConversationRestHandler}。</p>
 */
public class ConversationHandler implements RequestHandler {

    private final IConversationManager conversationManager;
    private final IConversationAccessChecker accessChecker;

    public ConversationHandler(IConversationManager conversationManager) {
        this(conversationManager, null);
    }

    public ConversationHandler(IConversationManager conversationManager, IConversationAccessChecker accessChecker) {
        this.conversationManager = conversationManager;
        this.accessChecker = accessChecker;
    }

    @Override
    public Object handle(ApiRequest req) {
        return switch (req.operation()) {
            case "conversation.list" -> handleList(req);
            case "conversation.set" -> handleSet(req);
            case "conversation.read" -> handleRead(req);
            default -> throw new NotFoundException("unsupported: " + req.operation());
        };
    }

    private Object handleRead(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String conversationId = req.getString("conversationId");
        long readSeq = req.getLong("readSeq", 0);

        conversationId = Preconditions.requireText(conversationId, "conversationId");

        requireReadable(userId, conversationId);
        long currentReadSeq = conversationManager.getReadSeq(userId, conversationId);
        conversationManager.markRead(userId, conversationId, Math.max(currentReadSeq, readSeq));
        int unreadCount = conversationManager.getUnreadCount(userId, conversationId);

        return Map.of(
                "conversationId", conversationId,
                "unreadCount", unreadCount
        );
    }

    private Object handleList(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        List<Conversation> conversations = conversationManager.getConversations(userId);
        return Map.of("userId", userId, "conversations", conversations, "count", conversations.size());
    }

    private Object handleSet(ApiRequest req) {
        String userId = RequestPreconditions.requireUser(req);
        String conversationId = req.getString("conversationId");
        conversationId = Preconditions.requireText(conversationId, "conversationId");
        requireReadable(userId, conversationId);
        if (req.params().containsKey("pinned")) {
            conversationManager.setPinned(userId, conversationId, req.getBoolean("pinned", false));
        }
        if (req.params().containsKey("recvMsgOpt")) {
            conversationManager.setRecvMsgOpt(userId, conversationId, req.getInt("recvMsgOpt", 0));
        }
        return Map.of("conversationId", conversationId, "status", "OK");
    }

    private void requireReadable(String userId, String conversationId) {
        if (accessChecker != null) {
            accessChecker.requireReadable(userId, conversationId);
        }
    }
}
