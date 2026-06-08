package com.im.core.access;

import com.im.api.Conversation;
import com.im.api.ConversationIds;
import com.im.api.IConversationAccessChecker;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ForbiddenException;
import com.im.common.validation.Preconditions;

import java.util.List;

/**
 * Default conversation access policy used by message read/search/read-receipt handlers.
 */
public class ConversationAccessChecker implements IConversationAccessChecker {

    private static final String SINGLE_PREFIX = "single_";
    private static final String GROUP_PREFIX = "group_";

    private final IConversationManager conversationManager;
    private final IGroupManager groupManager;

    public ConversationAccessChecker(IConversationManager conversationManager, IGroupManager groupManager) {
        this.conversationManager = conversationManager;
        this.groupManager = groupManager;
    }

    @Override
    public void requireReadable(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("not authenticated");
        }
        conversationId = Preconditions.requireText(conversationId, "conversationId");
        if (!canRead(userId, conversationId)) {
            throw new ForbiddenException("conversation not readable");
        }
    }

    @Override
    public List<String> listReadableConversationIds(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("not authenticated");
        }
        if (conversationManager == null) {
            return List.of();
        }
        return conversationManager.getConversations(userId).stream()
                .map(Conversation::getConversationId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private boolean canRead(String userId, String conversationId) {
        if (conversationManager != null && conversationManager.getConversation(userId, conversationId) != null) {
            return true;
        }
        if (isSingleParticipant(userId, conversationId)) {
            return true;
        }
        String groupId = groupIdFromConversation(conversationId);
        return groupId != null && groupManager != null && groupManager.isMember(groupId, userId);
    }

    private boolean isSingleParticipant(String userId, String conversationId) {
        if (!conversationId.startsWith(SINGLE_PREFIX)) {
            return false;
        }
        String ids = conversationId.substring(SINGLE_PREFIX.length());
        for (int i = 0; i < ids.length(); i++) {
            if (ids.charAt(i) != '_') {
                continue;
            }
            String left = ids.substring(0, i);
            String right = ids.substring(i + 1);
            if (conversationId.equals(ConversationIds.single(left, right))
                    && (userId.equals(left) || userId.equals(right))) {
                return true;
            }
        }
        return false;
    }

    private String groupIdFromConversation(String conversationId) {
        if (!conversationId.startsWith(GROUP_PREFIX) || conversationId.length() <= GROUP_PREFIX.length()) {
            return null;
        }
        return conversationId.substring(GROUP_PREFIX.length());
    }
}
