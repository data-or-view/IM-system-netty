package com.im.api;

import java.util.List;

/**
 * Group-chat message storage port.
 *
 * <p>This is a domain-facing abstraction. It does not imply a separate physical group
 * message table today, but keeps that option open for future scaling.</p>
 */
public interface IGroupMessageStore {

    /**
     * Persist a group-chat message.
     */
    void saveGroupMessage(Message message);

    /**
     * Pull group-chat history by group sequence.
     */
    default List<Message> pullGroupHistory(String groupId, long startSeq, long endSeq, int limit) {
        throw new UnsupportedOperationException("pullGroupHistory not implemented");
    }

    /**
     * Revoke a group-chat message.
     */
    default boolean revokeGroupMessage(String groupId, long seq, String revokerId, int role, String nickname) {
        throw new UnsupportedOperationException("revokeGroupMessage not implemented");
    }
}
