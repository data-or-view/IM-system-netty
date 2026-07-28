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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void simultaneousStartsProduceOneReservationAndOneRoomCreation() throws Exception {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1", "u2"));
        FakeCallManager calls = new FakeCallManager();
        GroupCallManager manager = new GroupCallManager(groups, calls, new InMemoryGroupCallStateStore(), 16);

        List<GroupCallSession> sessions = callConcurrently(2, () -> manager.start("u1", "g1", "video"));

        assertEquals(1, sessions.stream().map(GroupCallSession::roomId).distinct().count());
        assertEquals(1, calls.createRoomCalls.get());
    }

    @Test
    void concurrentJoinNeverExceedsMaximumAndRetryIsIdempotent() throws Exception {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("owner", "u1", "u2", "u3", "u4", "u5", "u6", "u7"));
        InMemoryGroupCallStateStore store = new InMemoryGroupCallStateStore();
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), store, 2);
        manager.start("owner", "g1", "video");

        AtomicInteger userIndex = new AtomicInteger(1);
        callConcurrently(7, () -> {
            try {
                return manager.join("u" + userIndex.getAndIncrement(), "g1");
            } catch (ForbiddenException expected) {
                return null;
            }
        });

        assertTrue(manager.active("owner", "g1").participantCount() <= 2);
        String admittedUser = manager.active("owner", "g1").participants().stream()
                .map(GroupCallParticipant::userId)
                .filter(userId -> !"owner".equals(userId))
                .findFirst()
                .orElseThrow();
        manager.join(admittedUser, "g1");
        assertEquals(2, manager.active("owner", "g1").participantCount());
    }

    @Test
    void joinDoesNotIssueTokenWhenAtomicAdmissionDidNotAddMember() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1"));
        GroupCallSession active = new GroupCallSession("g1", "room1", "video", "u1", "ws://livekit.test",
                1L, 1L, 1, List.of(new GroupCallParticipant("u1", 1L)), false);
        GroupCallStateStore store = new GroupCallStateStore() {
            @Override public GroupCallSession getActiveByGroup(String groupId) { return active; }
            @Override public GroupCallReservation reserve(GroupCallSession session) { return new GroupCallReservation(active, false); }
            @Override public GroupCallSession activate(String groupId, String roomId, String sfuEndpoint, long now) { return active; }
            @Override public GroupCallAdmission admit(String groupId, String userId, int maxParticipants, long now) {
                return new GroupCallAdmission(active, false, false);
            }
            @Override public GroupCallSession removeParticipant(String groupId, String userId) { return active; }
            @Override public GroupCallSession end(String groupId) { return active.markEnded(); }
        };
        GroupCallManager manager = new GroupCallManager(groups, new FakeCallManager(), store, 16);

        assertThrows(ValidationException.class, () -> manager.join("u1", "g1"));
    }

    private static <T> List<T> callConcurrently(int count, Callable<T> operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return operation.call();
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            java.util.ArrayList<T> results = new java.util.ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakeCallManager implements ICallManager {
        private final AtomicInteger createRoomCalls = new AtomicInteger();

        @Override
        public RoomInformation createRoom(String callerId, String calleeId, String roomId) {
            createRoomCalls.incrementAndGet();
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
        @Override public com.im.api.GroupDisbandResult disbandGroup(String groupId, String operatorId) { throw new UnsupportedOperationException(); }
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) { throw new UnsupportedOperationException(); }
        @Override public void addMember(String groupId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void addMembers(String groupId, List<String> userIds) { throw new UnsupportedOperationException(); }
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) { throw new UnsupportedOperationException(); }
        @Override public boolean quitGroup(String groupId, String userId) { throw new UnsupportedOperationException(); }
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
