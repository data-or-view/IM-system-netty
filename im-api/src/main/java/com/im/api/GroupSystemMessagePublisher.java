package com.im.api;

public interface GroupSystemMessagePublisher {

    GroupSystemMessagePublisher NOOP = new GroupSystemMessagePublisher() {
        @Override public void memberJoined(String groupId, String userId, String operatorId) {}
    };

    void memberJoined(String groupId, String userId, String operatorId);

    default void memberLeft(String groupId, String userId, String operatorId) {
    }

    default void groupInfoUpdated(String groupId, String operatorId) {
    }

    default void roleChanged(String groupId, String targetUserId, String operatorId, GroupMemberRole roleLevel) {
    }

    default void ownerTransferred(String groupId, String oldOwnerId, String newOwnerId) {
    }
}
