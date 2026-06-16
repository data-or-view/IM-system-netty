package com.im.api;

public interface GroupSystemMessagePublisher {

    GroupSystemMessagePublisher NOOP = (groupId, userId, operatorId) -> {};

    void memberJoined(String groupId, String userId, String operatorId);

    default void memberLeft(String groupId, String userId, String operatorId) {
    }
}
