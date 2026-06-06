package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ApplyHandleResult;
import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Operation;
import com.im.common.exception.ForbiddenException;
import com.im.core.group.GroupSystemMessagePublisher;
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

        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {
            createdGroupId = groupId;
        }
        @Override public void disbandGroup(String groupId, String operatorId) {}
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public void quitGroup(String groupId, String userId) {}
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
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
        String groupId;
        String userId;
        String operatorId;

        @Override
        public void memberJoined(String groupId, String userId, String operatorId) {
            memberJoinedCalls++;
            this.groupId = groupId;
            this.userId = userId;
            this.operatorId = operatorId;
        }
    }
}
