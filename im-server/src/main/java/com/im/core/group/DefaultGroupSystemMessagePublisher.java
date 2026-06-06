package com.im.core.group;

import com.im.api.content.SystemContent;
import com.im.core.usecase.SendMessageUseCase;

public class DefaultGroupSystemMessagePublisher implements GroupSystemMessagePublisher {

    public static final String SYSTEM_USER_ID = "im-system";
    public static final String MEMBER_JOINED = "group_member_joined";

    private final SendMessageUseCase sendMessageUseCase;

    public DefaultGroupSystemMessagePublisher(SendMessageUseCase sendMessageUseCase) {
        this.sendMessageUseCase = sendMessageUseCase;
    }

    @Override
    public void memberJoined(String groupId, String userId, String operatorId) {
        if (sendMessageUseCase == null || groupId == null || userId == null) return;
        String message = userId.equals(operatorId)
                ? userId + " joined the group"
                : operatorId + " approved " + userId + " to join the group";
        // System messages must travel through the same message pipeline as normal
        // group chat so history, unread counts, and online push stay consistent.
        sendMessageUseCase.publishGroupSystem(SYSTEM_USER_ID, groupId, new SystemContent(MEMBER_JOINED, message));
    }
}
