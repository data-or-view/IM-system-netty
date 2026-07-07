package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ICallManager;
import com.im.api.IChatSendPolicy;
import com.im.api.IGroupManager;
import com.im.api.IMessageQueue;
import com.im.api.ISequenceManager;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.Operation;
import com.im.api.QueueMessageHandler;
import com.im.api.RoomInformation;
import com.im.core.handler.WebhookService;
import com.im.core.call.GroupCallParticipant;
import com.im.core.call.GroupCallSession;
import com.im.core.call.GroupCallManager;
import com.im.core.call.GroupCallStateStore;
import com.im.core.usecase.SendMessageUseCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupCallHandlerTest {

    @Test
    void startReturnsActiveGroupCallPayload() {
        GroupCallHandler handler = new GroupCallHandler(manager());
        ApiRequest request = request(Operation.GROUP_CALL_START,
                Map.of("groupId", "g1", "callType", "video"), "u1");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(request);

        assertEquals(true, response.get("active"));
        assertEquals("g1", response.get("groupId"));
        assertEquals("video", response.get("callType"));
        assertEquals("u1", response.get("initiatorUserId"));
        assertEquals(1, response.get("participantCount"));
        assertTrue(response.containsKey("updatedAt"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> participants = (List<Map<String, Object>>) response.get("participants");
        assertEquals("u1", participants.get(0).get("userId"));
    }

    @Test
    void joinReturnsTokenAndEndpoint() {
        GroupCallManager manager = manager();
        new GroupCallHandler(manager).handle(request(Operation.GROUP_CALL_START,
                Map.of("groupId", "g1", "callType", "video"), "u1"));
        GroupCallHandler handler = new GroupCallHandler(manager);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(
                request(Operation.GROUP_CALL_JOIN, Map.of("groupId", "g1"), "u2"));

        assertTrue(response.get("token").toString().startsWith("token-u2-"));
        assertEquals("ws://livekit.test", response.get("sfuEndpoint"));
        assertEquals(2, response.get("participantCount"));
    }

    @Test
    void activeReturnsExplicitInactivePayload() {
        GroupCallHandler handler = new GroupCallHandler(manager());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) handler.handle(
                request(Operation.GROUP_CALL_ACTIVE, Map.of("groupId", "g1"), "u1"));

        assertEquals(false, response.get("active"));
        assertEquals(true, response.get("ended"));
        assertEquals("g1", response.get("groupId"));
    }

    @Test
    void joinPublishesGroupSignalWithGeneratedClientMsgId() {
        GroupCallManager manager = manager();
        new GroupCallHandler(manager).handle(request(Operation.GROUP_CALL_START,
                Map.of("groupId", "g1", "callType", "video"), "u1"));
        RecordingQueue queue = new RecordingQueue();
        SendMessageUseCase sendMessage = new SendMessageUseCase(
                queue,
                new FixedSequenceManager(),
                new WebhookService(null),
                new AllowAllSendPolicy());
        GroupCallHandler handler = new GroupCallHandler(manager, sendMessage);

        handler.handle(request(Operation.GROUP_CALL_JOIN, Map.of("groupId", "g1"), "u2"));

        assertEquals(2, queue.messages.size());
        Message signal = queue.messages.get(0);
        assertEquals(MessageQueueTopics.PERSIST, queue.topics.get(0));
        assertTrue(signal.getMessageId().matches("[A-Za-z0-9._:-]{8,64}"));
        assertEquals("u2", signal.getFromUserId());
        assertEquals("g1", signal.getGroupId());
        assertEquals("group_g1", signal.getConversationId());
        assertTrue(new String(signal.getBody()).contains("ACCEPT"));
    }

    private static GroupCallManager manager() {
        FakeGroupManager groups = new FakeGroupManager();
        groups.members.put("g1", Set.of("u1", "u2"));
        groups.roles.put("g1:u1", "member");
        groups.roles.put("g1:u2", "member");
        return new GroupCallManager(groups, new FakeCallManager(), new TestGroupCallStateStore(), 16);
    }

    private static ApiRequest request(Operation operation, Map<String, Object> params, String userId) {
        ApiRequest request = new ApiRequest(operation, params, Map.of(), null, null);
        request.setAttribute(ApiRequest.ATTR_USER_ID, userId);
        return request;
    }

    private static final class FakeCallManager implements ICallManager {
        @Override public RoomInformation createRoom(String callerId, String calleeId, String roomId) { return new RoomInformation(roomId, getSfuEndpoint(), "token-" + callerId + "-" + roomId, null); }
        @Override public String issueToken(String userId, String roomId) { return "token-" + userId + "-" + roomId; }
        @Override public String getProviderName() { return "fake"; }
        @Override public String getSfuEndpoint() { return "ws://livekit.test"; }
    }

    private static final class TestGroupCallStateStore implements GroupCallStateStore {
        private GroupCallSession session;
        private final Set<String> participants = new java.util.HashSet<>();

        @Override public GroupCallSession getActiveByGroup(String groupId) { return session; }

        @Override
        public GroupCallSession createIfAbsent(GroupCallSession session) {
            if (this.session != null) return this.session;
            this.session = session;
            participants.add(session.initiatorUserId());
            return session;
        }

        @Override
        public GroupCallSession addParticipant(String groupId, String userId) {
            participants.add(userId);
            session = session.withParticipants(participants.stream()
                    .map(user -> new GroupCallParticipant(user, System.currentTimeMillis()))
                    .toList());
            return session;
        }

        @Override
        public GroupCallSession removeParticipant(String groupId, String userId) {
            participants.remove(userId);
            session = session.withParticipants(participants.stream()
                    .map(user -> new GroupCallParticipant(user, System.currentTimeMillis()))
                    .toList());
            return session;
        }

        @Override
        public GroupCallSession end(String groupId) {
            GroupCallSession ended = session.markEnded();
            session = null;
            participants.clear();
            return ended;
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

    private static final class RecordingQueue implements IMessageQueue {
        private final List<String> topics = new ArrayList<>();
        private final List<Message> messages = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}

        @Override
        public void publish(String topic, Message message) {
            topics.add(topic);
            messages.add(message);
        }

        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private static final class FixedSequenceManager implements ISequenceManager {
        private long next = 1;

        @Override
        public long nextSequence(String conversationId) {
            return next++;
        }

        @Override
        public long getMaximumSequence(String conversationId) {
            return next - 1;
        }
    }

    private static final class AllowAllSendPolicy implements IChatSendPolicy {
        @Override public void requireCanSendSingle(String fromUserId, String toUserId) {}
        @Override public void requireCanSendGroup(String fromUserId, String groupId) {}
    }
}
