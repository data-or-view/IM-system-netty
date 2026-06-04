package com.im.api;

/**
 * Authorization boundary for chat message sending.
 */
public interface IChatSendPolicy {

    /**
     * Ensures the sender can send a single chat message to the target user.
     */
    void requireCanSendSingle(String fromUserId, String toUserId);

    /**
     * Ensures the sender can send a group chat message to the group.
     */
    void requireCanSendGroup(String fromUserId, String groupId);
}
