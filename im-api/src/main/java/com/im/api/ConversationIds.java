package com.im.api;

/**
 * Centralized conversation id rules shared by send, persist, and storage paths.
 */
public final class ConversationIds {

    private ConversationIds() {
    }

    public static String single(String userA, String userB) {
        if (userA == null || userB == null) {
            return null;
        }
        if (userA.compareTo(userB) <= 0) {
            return "single_" + userA + "_" + userB;
        }
        return "single_" + userB + "_" + userA;
    }

    public static String group(String groupId) {
        return groupId != null ? "group_" + groupId : null;
    }

    public static String fromMessageParties(String fromUserId, String toUserId, String groupId) {
        return groupId != null ? group(groupId) : single(fromUserId, toUserId);
    }
}
