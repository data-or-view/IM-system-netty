package com.im.api;

import java.util.List;

/**
 * Conversation-level access boundary for message read/search/read-receipt operations.
 */
public interface IConversationAccessChecker {

    /**
     * Ensures the user can read the given conversation.
     */
    void requireReadable(String userId, String conversationId);

    /**
     * Returns conversation IDs the user can read.
     */
    List<String> listReadableConversationIds(String userId);
}
