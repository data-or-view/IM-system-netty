package com.im.api;

import java.util.List;

/**
 * Single-chat message storage port.
 *
 * <p>This interface is intentionally independent from the current physical table layout.
 * Implementations may delegate to a unified message table today and move to a dedicated
 * single-chat table or shard later without changing the send path.</p>
 */
public interface ISingleMessageStore {

    /**
     * Persist a single-chat message.
     */
    void saveSingleMessage(Message message);

    /**
     * Pull single-chat history by conversation sequence.
     */
    default List<Message> pullSingleHistory(String userA, String userB, long startSeq, long endSeq, int limit) {
        throw new UnsupportedOperationException("pullSingleHistory not implemented");
    }

    /**
     * Revoke a single-chat message.
     */
    default boolean revokeSingleMessage(String conversationId, long seq, String revokerId, String nickname) {
        throw new UnsupportedOperationException("revokeSingleMessage not implemented");
    }
}
