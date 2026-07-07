package com.im.core.group;

import com.im.api.ConversationIds;
import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupApplyNotifier;
import com.im.api.GroupDisbandResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberRole;
import com.im.api.GroupSystemMessagePublisher;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.SystemMessage;
import com.im.common.exception.ForbiddenException;
import com.im.common.id.IdGenerator;
import com.im.core.system.SystemMessagePublishUseCase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Coordinates group mutations with their conversation, notification, and system-message side effects.
 */
public final class GroupWorkflow {

    public record CreateResult(String groupId, GroupInformation groupInformation) {
    }

    private final IGroupManager groupManager;
    private final GroupApplyNotifier groupApplyNotifier;
    private final GroupSystemMessagePublisher groupSystemMessagePublisher;
    private final IConversationManager conversationManager;
    private final SystemMessagePublishUseCase systemMessagePublishUseCase;

    public GroupWorkflow(IGroupManager groupManager,
                         GroupApplyNotifier groupApplyNotifier,
                         GroupSystemMessagePublisher groupSystemMessagePublisher,
                         IConversationManager conversationManager,
                         SystemMessagePublishUseCase systemMessagePublishUseCase) {
        this.groupManager = groupManager;
        this.groupApplyNotifier = groupApplyNotifier != null ? groupApplyNotifier : GroupApplyNotifier.NOOP;
        this.groupSystemMessagePublisher = groupSystemMessagePublisher != null
                ? groupSystemMessagePublisher : GroupSystemMessagePublisher.NOOP;
        this.conversationManager = conversationManager;
        this.systemMessagePublishUseCase = systemMessagePublishUseCase;
    }

    public CreateResult createGroup(String ownerId,
                                    String groupName,
                                    String faceUrl,
                                    int groupType,
                                    int needVerification,
                                    List<String> members) {
        String groupId = IdGenerator.groupId();
        List<String> safeMembers = members != null ? members : List.of();

        groupManager.createGroup(groupId, ownerId, groupName, faceUrl, safeMembers, groupType, needVerification);
        List<String> allMemberIds = normalizeInitialMemberIds(ownerId, safeMembers);
        if (conversationManager != null) {
            conversationManager.createGroupConversations(allMemberIds, groupId, ConversationIds.group(groupId));
        }
        groupSystemMessagePublisher.groupCreated(groupId, ownerId, allMemberIds);
        publishGroupCreatedMessage(groupId, groupName, ownerId, allMemberIds);
        return new CreateResult(groupId, groupManager.getGroupInformation(groupId));
    }

    public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) {
        GroupJoinResult result = groupManager.joinGroup(groupId, userId, reqMsg);
        if (result == GroupJoinResult.JOINED) {
            groupSystemMessagePublisher.memberJoined(groupId, userId, userId);
        }
        if (result == GroupJoinResult.APPLY_CREATED) {
            GroupApply apply = findGroupApply(groupId, userId, true);
            if (apply != null) {
                groupApplyNotifier.notifyApplyCreated(groupManager.getManagerIds(groupId), apply);
            }
        }
        return result;
    }

    public boolean quitGroup(String groupId, String userId) {
        boolean removed = groupManager.quitGroup(groupId, userId);
        if (removed) {
            deleteConversation(userId, groupId);
            groupSystemMessagePublisher.memberLeft(groupId, userId, userId);
        }
        return removed;
    }

    public void kickMember(String groupId, String operatorId, String targetUserId) {
        groupManager.kickMember(groupId, operatorId, targetUserId);
        deleteConversation(targetUserId, groupId);
        groupSystemMessagePublisher.memberLeft(groupId, targetUserId, operatorId);
    }

    public void disbandGroup(String groupId, String operatorId) {
        GroupDisbandResult result = groupManager.disbandGroup(groupId, operatorId);
        for (String memberId : result.getAffectedMemberIds()) {
            deleteConversation(memberId, groupId);
        }
        publishGroupDisbandedMessage(result);
    }

    public void updateGroupInfo(String groupId,
                                String groupName,
                                String notification,
                                String introduction,
                                String faceUrl,
                                int needVerification,
                                int lookMemberInfo,
                                int applyMemberFriend,
                                String operatorId) {
        groupManager.setGroupInformation(groupId, groupName, notification, introduction, faceUrl,
                needVerification, lookMemberInfo, applyMemberFriend, operatorId);
        groupSystemMessagePublisher.groupInfoUpdated(groupId, operatorId);
    }

    public void transferOwner(String groupId, String operatorId, String targetUserId) {
        groupManager.transferOwner(groupId, operatorId, targetUserId);
        groupSystemMessagePublisher.ownerTransferred(groupId, operatorId, targetUserId);
    }

    public void setMemberRole(String groupId, String operatorId, String targetUserId, GroupMemberRole role) {
        groupManager.setMemberRole(groupId, operatorId, targetUserId, role.getCode());
        groupSystemMessagePublisher.roleChanged(groupId, targetUserId, operatorId, role);
    }

    public void updateMemberInfo(String groupId, String userId, String nickname) {
        groupManager.setMemberInfo(groupId, userId, nickname);
    }

    public void muteAll(String groupId, String operatorId, boolean mute) {
        groupManager.muteGroupAll(groupId, operatorId, mute);
    }

    public void approveApply(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) {
        requireGroupAdmin(groupId, operatorId);
        GroupApplyHandleResult result = groupManager.respondJoinRequest(groupId, userId, operatorId, handleMsg, agreed);
        if (result != GroupApplyHandleResult.HANDLED) {
            return;
        }

        GroupApply apply = findGroupApply(groupId, userId, false);
        if (apply != null) {
            groupApplyNotifier.notifyApplyHandled(userId, apply);
        }
        if (agreed) {
            groupSystemMessagePublisher.memberJoined(groupId, userId, operatorId);
        }
    }

    private void deleteConversation(String userId, String groupId) {
        if (conversationManager != null) {
            conversationManager.deleteConversation(userId, ConversationIds.group(groupId));
        }
    }

    private List<String> normalizeInitialMemberIds(String ownerId, List<String> members) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (ownerId != null && !ownerId.isBlank()) {
            ids.add(ownerId);
        }
        for (String memberId : members) {
            if (memberId != null && !memberId.isBlank()) {
                ids.add(memberId);
            }
        }
        return new ArrayList<>(ids);
    }

    private void publishGroupCreatedMessage(String groupId, String groupName, String ownerId, List<String> allMemberIds) {
        if (systemMessagePublishUseCase == null) return;
        List<String> invitedMemberIds = allMemberIds.stream()
                .filter(memberId -> !memberId.equals(ownerId))
                .toList();
        if (invitedMemberIds.isEmpty()) return;

        SystemMessage message = new SystemMessage();
        message.setChannelId("group");
        message.setTitle("你已加入群聊");
        String displayName = groupName != null && !groupName.isBlank() ? groupName : groupId;
        message.setSummary(ownerId + " 邀请你加入「" + displayName + "」");
        message.setContent(ownerId + " 邀请你加入群聊「" + displayName + "」");
        message.setContentType("group_invited");
        message.setSenderId(ownerId);
        systemMessagePublishUseCase.publishToUsers(message, invitedMemberIds);
    }

    private void publishGroupDisbandedMessage(GroupDisbandResult result) {
        if (systemMessagePublishUseCase == null || result.getAffectedMemberIds().isEmpty()) return;
        SystemMessage message = new SystemMessage();
        message.setChannelId("group");
        message.setTitle("群聊已解散");
        String groupName = result.getGroupName() != null && !result.getGroupName().isBlank()
                ? result.getGroupName() : result.getGroupId();
        message.setSummary(groupName + "已解散");
        message.setContent(groupName + "已被群主解散");
        message.setContentType("group_disbanded");
        message.setSenderId(result.getOperatorId());
        systemMessagePublishUseCase.publishToUsers(message, result.getAffectedMemberIds());
    }

    private GroupApply findGroupApply(String groupId, String userId, boolean onlyPending) {
        return groupManager.getJoinRequests(groupId, onlyPending).stream()
                .filter(apply -> userId.equals(apply.getUserId()))
                .findFirst()
                .orElse(null);
    }

    private void requireGroupAdmin(String groupId, String operatorId) {
        String role = groupManager.getRole(groupId, operatorId);
        if (!"owner".equals(role) && !"admin".equals(role)) {
            throw new ForbiddenException("only group owner or admin can operate group apply");
        }
    }
}
