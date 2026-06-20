package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ApplyHandleResult;
import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupDisbandResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.GroupMemberRole;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Operation;
import com.im.api.Conversation;
import com.im.api.Message;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.NotFoundException;
import com.im.api.GroupSystemMessagePublisher;
import com.im.core.system.SystemMessagePublishUseCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupHandlerApplyTest {

    @Test
    void ownerCanListManageablePendingGroupApplies() {
        RecordingGroupManager manager = new RecordingGroupManager();
        GroupApply apply = new GroupApply();
        apply.setGroupId("grp_1");
        apply.setUserId("alice");
        apply.setHandleResult(ApplyHandleResult.PENDING);
        manager.manageableApplies = List.of(apply);
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_APPLY_LIST, Map.of("onlyPending", true), "owner");

        Object response = handler.handle(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response;
        @SuppressWarnings("unchecked")
        List<GroupApply> applies = (List<GroupApply>) body.get("applies");
        assertEquals("owner", body.get("operatorId"));
        assertEquals(1, body.get("count"));
        assertEquals("alice", applies.get(0).getUserId());
        assertEquals("owner", manager.manageableOperatorId);
        assertEquals(true, manager.manageableOnlyPending);
    }

    @Test
    void nonAdminCannotApproveGroupApply() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.role = "member";
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_APPLY_APPROVE,
                Map.of("groupId", "grp_1", "userId", "alice", "agreed", true), "bob");

        assertThrows(ForbiddenException.class, () -> handler.handle(request));
        assertEquals(0, manager.respondCalls);
    }

    @Test
    void adminCanApproveGroupApply() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.role = "admin";
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_APPLY_APPROVE,
                Map.of("groupId", "grp_1", "userId", "alice", "agreed", true, "handleMsg", "ok"), "admin");

        handler.handle(request);

        assertEquals(1, manager.respondCalls);
        assertEquals("grp_1", manager.respondGroupId);
        assertEquals("alice", manager.respondUserId);
        assertEquals("admin", manager.respondOperatorId);
        assertEquals("ok", manager.respondHandleMsg);
        assertEquals(true, manager.respondAgreed);
    }

    @Test
    void directJoinPublishesGroupSystemMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.joinResult = GroupJoinResult.JOINED;
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        GroupHandler handler = new GroupHandler(manager, null, publisher);
        ApiRequest request = request(Operation.GROUP_JOIN, Map.of("groupId", "grp_1"), "alice");

        handler.handle(request);

        assertEquals(1, publisher.memberJoinedCalls);
        assertEquals("grp_1", publisher.groupId);
        assertEquals("alice", publisher.userId);
        assertEquals("alice", publisher.operatorId);
    }

    @Test
    void approvingJoinPublishesGroupSystemMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.role = "admin";
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        GroupHandler handler = new GroupHandler(manager, null, publisher);
        ApiRequest request = request(Operation.GROUP_APPLY_APPROVE,
                Map.of("groupId", "grp_1", "userId", "alice", "agreed", true), "admin");

        handler.handle(request);

        assertEquals(1, publisher.memberJoinedCalls);
        assertEquals("grp_1", publisher.groupId);
        assertEquals("alice", publisher.userId);
        assertEquals("admin", publisher.operatorId);
    }

    @Test
    void createGroupGeneratesGroupIdOnServerAndIgnoresClientGroupId() {
        RecordingGroupManager manager = new RecordingGroupManager();
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_CREATE,
                Map.of("groupId", "client-picked", "groupName", "demo"), "owner");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertTrue(manager.createdGroupId.matches("grp_[0-9a-z]+_[0-9a-z]{8}"));
        assertEquals(manager.createdGroupId, response.get("groupId"));
    }

    @Test
    void creatingGroupCreatesConversationsAndNotifiesInvitedMembers() {
        RecordingGroupManager manager = new RecordingGroupManager();
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        RecordingSystemMessageStore systemMessageStore = new RecordingSystemMessageStore();
        GroupHandler handler = new GroupHandler(manager, null, publisher, conversationManager,
                new SystemMessagePublishUseCase(systemMessageStore, null));
        ApiRequest request = request(Operation.GROUP_CREATE,
                Map.of("groupName", "研发群", "members", List.of("alice", "bob", "owner")), "owner");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);
        String groupId = (String) response.get("groupId");

        assertEquals(groupId, conversationManager.createdGroupId);
        assertEquals("group_" + groupId, conversationManager.createdConversationId);
        assertEquals(List.of("owner", "alice", "bob"), conversationManager.createdMemberIds);
        assertEquals(1, publisher.groupCreatedCalls);
        assertEquals(groupId, publisher.groupId);
        assertEquals("owner", publisher.operatorId);
        assertEquals(List.of("owner", "alice", "bob"), publisher.groupCreatedMemberIds);
        assertEquals("group", systemMessageStore.channelId);
        assertEquals("你已加入群聊", systemMessageStore.title);
        assertEquals("owner 邀请你加入群聊「研发群」", systemMessageStore.content);
        assertEquals(List.of("alice", "bob"), systemMessageStore.inboxUserIds);
    }

    @Test
    void quittingGroupPublishesLeftMessageAndRemovesOwnConversation() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.quitResult = true;
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        GroupHandler handler = new GroupHandler(manager, null, publisher, conversationManager);
        ApiRequest request = request(Operation.GROUP_QUIT, Map.of("groupId", "grp_1"), "alice");

        handler.handle(request);

        assertEquals(1, publisher.memberLeftCalls);
        assertEquals("grp_1", publisher.groupId);
        assertEquals("alice", publisher.userId);
        assertEquals("alice", publisher.operatorId);
        assertEquals("alice", conversationManager.deletedOwnerUserId);
        assertEquals("group_grp_1", conversationManager.deletedConversationId);
    }

    @Test
    void quittingGroupDoesNotPublishOrRemoveConversationWhenUserWasNotMember() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.quitResult = false;
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        GroupHandler handler = new GroupHandler(manager, null, publisher, conversationManager);
        ApiRequest request = request(Operation.GROUP_QUIT, Map.of("groupId", "grp_1"), "alice");

        handler.handle(request);

        assertEquals(0, publisher.memberLeftCalls);
        assertEquals(null, conversationManager.deletedConversationId);
    }

    @Test
    void disbandingGroupRemovesConversationForAffectedMembersAndPublishesSystemMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.disbandResult = new GroupDisbandResult("grp_1", "owner", "研发群", List.of("owner", "alice"));
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        RecordingSystemMessageStore systemMessageStore = new RecordingSystemMessageStore();
        GroupHandler handler = new GroupHandler(manager, null, GroupSystemMessagePublisher.NOOP,
                conversationManager, new SystemMessagePublishUseCase(systemMessageStore, null));
        ApiRequest request = request(Operation.GROUP_DISBAND, Map.of("groupId", "grp_1"), "owner");

        handler.handle(request);

        assertEquals(List.of("owner:group_grp_1", "alice:group_grp_1"), conversationManager.deletedConversations);
        assertEquals("group", systemMessageStore.channelId);
        assertEquals("群聊已解散", systemMessageStore.title);
        assertEquals("研发群已被群主解散", systemMessageStore.content);
        assertEquals(List.of("owner", "alice"), systemMessageStore.inboxUserIds);
    }

    @Test
    void nonOwnerCannotDisbandGroup() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.disbandFailure = new ForbiddenException("only group owner can disband group");
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_DISBAND, Map.of("groupId", "grp_1"), "member");

        assertThrows(ForbiddenException.class, () -> handler.handle(request));
    }

    @Test
    void missingGroupCannotBeDisbanded() {
        RecordingGroupManager manager = new RecordingGroupManager();
        manager.disbandFailure = new NotFoundException("group not found");
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_DISBAND, Map.of("groupId", "missing"), "owner");

        assertThrows(NotFoundException.class, () -> handler.handle(request));
    }

    @Test
    void ownerTransferDelegatesAndPublishesRoleMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        GroupHandler handler = new GroupHandler(manager, null, publisher);
        ApiRequest request = request(Operation.GROUP_OWNER_TRANSFER,
                Map.of("groupId", "grp_1", "targetUserId", "alice"), "owner");

        handler.handle(request);

        assertEquals("grp_1", manager.transferGroupId);
        assertEquals("owner", manager.transferOldOwnerId);
        assertEquals("alice", manager.transferNewOwnerId);
        assertEquals(1, publisher.ownerTransferredCalls);
        assertEquals("grp_1", publisher.groupId);
        assertEquals("owner", publisher.operatorId);
        assertEquals("alice", publisher.userId);
    }

    @Test
    void memberRoleSetAcceptsAdminRoleAndPublishesRoleMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        GroupHandler handler = new GroupHandler(manager, null, publisher);
        ApiRequest request = request(Operation.GROUP_MEMBER_ROLE_SET,
                Map.of("groupId", "grp_1", "targetUserId", "alice", "roleLevel", "ADMIN"), "owner");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals("ADMIN", response.get("roleLevel"));
        assertEquals("grp_1", manager.roleSetGroupId);
        assertEquals("owner", manager.roleSetOperatorId);
        assertEquals("alice", manager.roleSetTargetUserId);
        assertEquals(GroupMemberRole.ADMIN.getCode(), manager.roleSetRoleLevel);
        assertEquals(1, publisher.roleChangedCalls);
        assertEquals(GroupMemberRole.ADMIN, publisher.roleLevel);
    }

    @Test
    void memberRoleSetRejectsOwnerRole() {
        RecordingGroupManager manager = new RecordingGroupManager();
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_MEMBER_ROLE_SET,
                Map.of("groupId", "grp_1", "targetUserId", "alice", "roleLevel", "OWNER"), "owner");

        assertThrows(com.im.common.exception.ValidationException.class, () -> handler.handle(request));
        assertEquals(0, manager.roleSetRoleLevel);
    }

    @Test
    void memberInfoUpdateDelegatesCurrentUserNickname() {
        RecordingGroupManager manager = new RecordingGroupManager();
        GroupHandler handler = new GroupHandler(manager);
        ApiRequest request = request(Operation.GROUP_MEMBER_INFO_UPDATE,
                Map.of("groupId", "grp_1", "nickname", "群内小王"), "alice");

        handler.handle(request);

        assertEquals("grp_1", manager.memberInfoGroupId);
        assertEquals("alice", manager.memberInfoUserId);
        assertEquals("群内小王", manager.memberInfoNickname);
    }

    @Test
    void updatingGroupInfoPublishesInfoUpdatedMessage() {
        RecordingGroupManager manager = new RecordingGroupManager();
        RecordingGroupSystemMessagePublisher publisher = new RecordingGroupSystemMessagePublisher();
        GroupHandler handler = new GroupHandler(manager, null, publisher);
        ApiRequest request = request(Operation.GROUP_INFO_UPDATE,
                Map.of("groupId", "grp_1", "groupName", "新群名"), "admin");

        handler.handle(request);

        assertEquals(1, publisher.groupInfoUpdatedCalls);
        assertEquals("grp_1", publisher.groupId);
        assertEquals("admin", publisher.operatorId);
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params, String userId) {
        ApiRequest request = new ApiRequest(operation, params, Map.of(), null, null);
        request.setAttribute("_uid", userId);
        return request;
    }

    private static class RecordingGroupManager implements IGroupManager {
        List<GroupApply> manageableApplies = List.of();
        String manageableOperatorId;
        boolean manageableOnlyPending;
        String role = "owner";
        int respondCalls;
        String respondGroupId;
        String respondUserId;
        String respondOperatorId;
        String respondHandleMsg;
        boolean respondAgreed;
        String createdGroupId;
        GroupJoinResult joinResult = GroupJoinResult.APPLY_CREATED;
        boolean quitResult = true;
        GroupDisbandResult disbandResult = new GroupDisbandResult("grp_1", "owner", "grp_1", List.of());
        RuntimeException disbandFailure;
        String transferGroupId;
        String transferOldOwnerId;
        String transferNewOwnerId;
        String roleSetGroupId;
        String roleSetOperatorId;
        String roleSetTargetUserId;
        int roleSetRoleLevel;
        String memberInfoGroupId;
        String memberInfoUserId;
        String memberInfoNickname;

        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {
            createdGroupId = groupId;
        }
        @Override public GroupDisbandResult disbandGroup(String groupId, String operatorId) {
            if (disbandFailure != null) throw disbandFailure;
            return disbandResult;
        }
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public boolean quitGroup(String groupId, String userId) { return quitResult; }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
            transferGroupId = groupId;
            transferOldOwnerId = oldOwnerId;
            transferNewOwnerId = newOwnerId;
        }
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {
            roleSetGroupId = groupId;
            roleSetOperatorId = operatorId;
            roleSetTargetUserId = targetUserId;
            roleSetRoleLevel = roleLevel;
        }
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public void setMemberInfo(String groupId, String userId, String ex) {
            memberInfoGroupId = groupId;
            memberInfoUserId = userId;
            memberInfoNickname = ex;
        }
        @Override public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { return joinResult; }

        @Override
        public GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) {
            respondCalls++;
            respondGroupId = groupId;
            respondUserId = userId;
            respondOperatorId = operatorId;
            respondHandleMsg = handleMsg;
            respondAgreed = agreed;
            return GroupApplyHandleResult.HANDLED;
        }

        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }

        @Override
        public List<GroupApply> getManageableJoinRequests(String operatorId, boolean onlyPending) {
            manageableOperatorId = operatorId;
            manageableOnlyPending = onlyPending;
            return manageableApplies;
        }

        @Override public List<GroupMemberInformation> getMemberList(String groupId) { return List.of(); }
        @Override public Set<String> getMemberIds(String groupId) { return Set.of(); }
        @Override public boolean isMember(String groupId, String userId) { return false; }
        @Override public String getRole(String groupId, String userId) { return role; }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }
        @Override public GroupInformation getGroupInformation(String groupId) { return null; }
        @Override public List<GroupInformation> searchGroups(String keyword, int limit) { return List.of(); }
        @Override public IncrementalSyncResult<String> getIncrementalGroups(String userId, long version) { return new IncrementalSyncResult<>(List.of(), version, false); }
        @Override public IncrementalSyncResult<GroupMemberInformation> getIncrementalMembers(String groupId, long version) { return new IncrementalSyncResult<>(List.of(), version, false); }
    }

    private static class RecordingGroupSystemMessagePublisher implements GroupSystemMessagePublisher {
        int memberJoinedCalls;
        int memberLeftCalls;
        int groupInfoUpdatedCalls;
        int roleChangedCalls;
        int ownerTransferredCalls;
        int groupCreatedCalls;
        String groupId;
        String userId;
        String operatorId;
        GroupMemberRole roleLevel;
        List<String> groupCreatedMemberIds = List.of();

        @Override
        public void memberJoined(String groupId, String userId, String operatorId) {
            memberJoinedCalls++;
            this.groupId = groupId;
            this.userId = userId;
            this.operatorId = operatorId;
        }

        @Override
        public void groupCreated(String groupId, String ownerId, List<String> memberIds) {
            groupCreatedCalls++;
            this.groupId = groupId;
            this.operatorId = ownerId;
            this.groupCreatedMemberIds = memberIds;
        }

        @Override
        public void memberLeft(String groupId, String userId, String operatorId) {
            memberLeftCalls++;
            this.groupId = groupId;
            this.userId = userId;
            this.operatorId = operatorId;
        }

        @Override
        public void groupInfoUpdated(String groupId, String operatorId) {
            groupInfoUpdatedCalls++;
            this.groupId = groupId;
            this.operatorId = operatorId;
        }

        @Override
        public void roleChanged(String groupId, String targetUserId, String operatorId, GroupMemberRole roleLevel) {
            roleChangedCalls++;
            this.groupId = groupId;
            this.userId = targetUserId;
            this.operatorId = operatorId;
            this.roleLevel = roleLevel;
        }

        @Override
        public void ownerTransferred(String groupId, String oldOwnerId, String newOwnerId) {
            ownerTransferredCalls++;
            this.groupId = groupId;
            this.operatorId = oldOwnerId;
            this.userId = newOwnerId;
        }
    }

    private static final class RecordingConversationManager implements IConversationManager {
        String deletedOwnerUserId;
        String deletedConversationId;
        List<String> deletedConversations = new java.util.ArrayList<>();
        String createdGroupId;
        String createdConversationId;
        List<String> createdMemberIds = List.of();

        @Override public List<Conversation> getConversations(String ownerUserId) { return List.of(); }
        @Override public Conversation getConversation(String ownerUserId, String conversationId) { return null; }
        @Override public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {}
        @Override public void markRead(String ownerUserId, String conversationId, long readSeq) {}
        @Override public void setPinned(String ownerUserId, String conversationId, boolean pinned) {}
        @Override public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {}
        @Override public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {}

        @Override
        public void createGroupConversations(List<String> memberIds, String groupId, String conversationId) {
            createdMemberIds = List.copyOf(memberIds);
            createdGroupId = groupId;
            createdConversationId = conversationId;
        }

        @Override
        public void deleteConversation(String ownerUserId, String conversationId) {
            deletedOwnerUserId = ownerUserId;
            deletedConversationId = conversationId;
            deletedConversations.add(ownerUserId + ":" + conversationId);
        }
    }

    private static final class RecordingSystemMessageStore implements com.im.api.ISystemMessageStore {
        String channelId;
        String title;
        String content;
        List<String> inboxUserIds = new java.util.ArrayList<>();

        @Override public List<com.im.api.SystemChannel> listChannels() { return List.of(); }
        @Override public void ensureChannel(com.im.api.SystemChannel channel) { channelId = channel.getChannelId(); }
        @Override public void saveMessage(com.im.api.SystemMessage message) {
            title = message.getTitle();
            content = message.getContent();
        }
        @Override public void addInbox(String messageId, String userId, String channelId, long createdAt) {
            inboxUserIds.add(userId);
        }
        @Override public List<com.im.api.SystemMessageInboxItem> listInbox(String userId, String channelId, boolean onlyUnread, int limit, long cursor) { return List.of(); }
        @Override public com.im.api.SystemMessageInboxItem getInboxMessage(String userId, String messageId) { return null; }
        @Override public void markRead(String userId, String messageId, long readAt) {}
        @Override public int markAllRead(String userId, String channelId, long readAt) { return 0; }
        @Override public int unreadCount(String userId, String channelId) { return 0; }
        @Override public Map<String, Integer> unreadCountByChannel(String userId) { return Map.of(); }
    }
}
