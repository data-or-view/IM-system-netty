package com.im.core.call;

import com.im.api.ICallManager;
import com.im.api.IGroupManager;
import com.im.api.RoomInformation;
import com.im.common.exception.ForbiddenException;
import com.im.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroupCallManagerTest {

    @Test
    void memberCanStartAndJoinGroupCall() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1", "u2"));
        InMemoryGroupCallStateStore store = new InMemoryGroupCallStateStore();
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), store, 16);

        GroupCallSession started = manager.start("u1", "g1", "video");
        GroupCallJoinResult joined = manager.join("u2", "g1");

        assertEquals("g1", started.groupId());
        assertEquals("u1", started.initiatorUserId());
        assertEquals("video", started.callType());
        assertEquals(started.roomId(), joined.session().roomId());
        assertEquals("token-u2-" + started.roomId(), joined.token());
        assertEquals(2, joined.session().participantCount());
    }

    @Test
    void nonMemberCannotStartOrJoin() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1"));
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), new InMemoryGroupCallStateStore(), 16);

        assertThrows(ForbiddenException.class, () -> manager.start("u2", "g1", "video"));
        assertThrows(ForbiddenException.class, () -> manager.join("u2", "g1"));
    }

    @Test
    void startReturnsExistingActiveCallForSameGroup() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1", "u2"));
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), new InMemoryGroupCallStateStore(), 16);

        GroupCallSession first = manager.start("u1", "g1", "video");
        GroupCallSession second = manager.start("u2", "g1", "video");

        assertEquals(first.roomId(), second.roomId());
        assertEquals("u1", second.initiatorUserId());
        assertEquals(1, second.participantCount());
    }

    @Test
    void onlyInitiatorOwnerOrAdminCanEnd() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("owner", "admin", "u1", "u2"));
        groups.roles.put("g1:owner", "owner");
        groups.roles.put("g1:admin", "admin");
        groups.roles.put("g1:u1", "member");
        groups.roles.put("g1:u2", "member");
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), new InMemoryGroupCallStateStore(), 16);
        GroupCallSession session = manager.start("u1", "g1", "video");

        assertThrows(ForbiddenException.class, () -> manager.end("u2", "g1"));
        assertTrue(manager.end("admin", "g1").ended());
        assertNull(manager.active("u1", "g1"));

        manager.start("u1", "g1", "video");
        assertTrue(manager.end("u1", "g1").ended());
        assertNotNull(session.roomId());
    }

    @Test
    void leavingLastParticipantEndsCall() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1", "u2"));
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), new InMemoryGroupCallStateStore(), 16);
        manager.start("u1", "g1", "video");
        manager.join("u2", "g1");

        GroupCallSession afterFirstLeave = manager.leave("u1", "g1");
        assertNotNull(afterFirstLeave);
        assertEquals(1, afterFirstLeave.participantCount());

        GroupCallSession afterLastLeave = manager.leave("u2", "g1");
        assertTrue(afterLastLeave.ended());
        assertNull(manager.active("u1", "g1"));
    }

    @Test
    void rejectsInvalidCallType() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1"));
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), new InMemoryGroupCallStateStore(), 16);

        assertThrows(ValidationException.class, () -> manager.start("u1", "g1", "screen"));
    }

    private static final class FakeCallManager implements ICallManager {
        @Override
        public RoomInformation createRoom(String callerId, String calleeId, String roomId) {
            String id = roomId != null ? roomId : "room_group_g1";
            return new RoomInformation(id, getSfuEndpoint(), "token-" + callerId + "-" + id, null);
        }

        @Override
        public String issueToken(String userId, String roomId) {
            return "token-" + userId + "-" + roomId;
        }

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public String getSfuEndpoint() {
            return "ws://livekit.test";
        }
    }

    private static final class FakeGroupManager implements IGroupManager {
        private final Map<String, Set<String>> members = new HashMap<>();
        private final Map<String, String> roles = new HashMap<>();

        @Override public boolean isMember(String groupId, String userId) { return members.getOrDefault(groupId, Set.of()).contains(userId); }
        @Override public String getRole(String groupId, String userId) { return roles.getOrDefault(groupId + ":" + userId, isMember(groupId, userId) ? "member" : null); }
        @Override public Set<String> getMemberIds(String groupId) { return members.getOrDefault(groupId, Set.of()); }

        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) { throw new UnsupportedOperationException(); }
        @Override public void disbandGroup(String groupId, String operatorId) { throw new UnsupportedOperationException(); }
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) { throw new UnsupportedOperationException(); }
        @Override public void addMember(String groupId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void addMembers(String groupId, List<String> userIds) { throw new UnsupportedOperationException(); }
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) { throw new UnsupportedOperationException(); }
        @Override public void quitGroup(String groupId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) { throw new UnsupportedOperationException(); }
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) { throw new UnsupportedOperationException(); }
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) { throw new UnsupportedOperationException(); }
        @Override public com.im.api.GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { throw new UnsupportedOperationException(); }
        @Override public com.im.api.GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) { throw new UnsupportedOperationException(); }
        @Override public List<com.im.api.GroupApply> getJoinRequests(String groupId, boolean onlyPending) { throw new UnsupportedOperationException(); }
        @Override public List<com.im.api.GroupMemberInformation> getMemberList(String groupId) { throw new UnsupportedOperationException(); }
        @Override public Set<String> getJoinedGroups(String userId) { throw new UnsupportedOperationException(); }
        @Override public com.im.api.GroupInformation getGroupInformation(String groupId) { throw new UnsupportedOperationException(); }
        @Override public List<com.im.api.GroupInformation> searchGroups(String keyword, int limit) { throw new UnsupportedOperationException(); }
    }
}
