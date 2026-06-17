package com.im.core.group;

import com.im.api.GroupSystemMessagePublisher;
import com.im.api.GroupMemberRole;
import com.im.api.content.SystemContent;
import com.im.core.usecase.SendMessageUseCase;

public class DefaultGroupSystemMessagePublisher implements GroupSystemMessagePublisher {

    public static final String SYSTEM_USER_ID = "im-system";
    public static final String MEMBER_JOINED = "group_member_joined";
    public static final String MEMBER_LEFT = "group_member_left";
    public static final String GROUP_INFO_UPDATED = "group_info_updated";
    public static final String GROUP_ROLE_CHANGED = "group_role_changed";

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

    @Override
    public void memberLeft(String groupId, String userId, String operatorId) {
        if (sendMessageUseCase == null || groupId == null || userId == null) return;
        String message = userId.equals(operatorId)
                ? userId + " left the group"
                : operatorId + " removed " + userId + " from the group";
        sendMessageUseCase.publishGroupSystem(SYSTEM_USER_ID, groupId, new SystemContent(MEMBER_LEFT, message));
    }

    @Override
    public void groupInfoUpdated(String groupId, String operatorId) {
        if (sendMessageUseCase == null || groupId == null || operatorId == null) return;
        sendMessageUseCase.publishGroupSystem(SYSTEM_USER_ID, groupId,
                new SystemContent(GROUP_INFO_UPDATED, operatorId + " updated group information"));
    }

    @Override
    public void roleChanged(String groupId, String targetUserId, String operatorId, GroupMemberRole roleLevel) {
        if (sendMessageUseCase == null || groupId == null || targetUserId == null) return;
        String role = roleLevel == GroupMemberRole.ADMIN ? "admin" : "member";
        sendMessageUseCase.publishGroupSystem(SYSTEM_USER_ID, groupId,
                new SystemContent(GROUP_ROLE_CHANGED, operatorId + " changed " + targetUserId + " to " + role));
    }

    @Override
    public void ownerTransferred(String groupId, String oldOwnerId, String newOwnerId) {
        if (sendMessageUseCase == null || groupId == null || oldOwnerId == null || newOwnerId == null) return;
        sendMessageUseCase.publishGroupSystem(SYSTEM_USER_ID, groupId,
                new SystemContent(GROUP_ROLE_CHANGED, oldOwnerId + " transferred group ownership to " + newOwnerId));
    }
}
