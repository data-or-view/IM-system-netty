package com.im.core.access;

import com.im.api.Conversation;
import com.im.api.ConversationType;
import com.im.api.IConversationManager;
import com.im.api.IGroupManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.Message;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationAccessCheckerTest {

    @Test
    void allowsConversationOwnedByUser() {
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        conversationManager.visibleConversations.put("alice|single_alice_bob",
                new Conversation("single_alice_bob", "alice", ConversationType.SINGLE));
        ConversationAccessChecker checker = new ConversationAccessChecker(conversationManager, new RecordingGroupManager());

        assertDoesNotThrow(() -> checker.requireReadable("alice", "single_alice_bob"));
    }

    @Test
    void allowsSingleConversationOnlyWhenCanonicalIdContainsUser() {
        ConversationAccessChecker checker = new ConversationAccessChecker(new RecordingConversationManager(), new RecordingGroupManager());

        assertDoesNotThrow(() -> checker.requireReadable("user_a", "single_user_a_user_b"));

        ImException ex = assertThrows(ImException.class,
                () -> checker.requireReadable("user", "single_user_a_user_b"));
        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void allowsGroupConversationForGroupMember() {
        RecordingGroupManager groupManager = new RecordingGroupManager();
        groupManager.members.add("group-1|alice");
        ConversationAccessChecker checker = new ConversationAccessChecker(new RecordingConversationManager(), groupManager);

        assertDoesNotThrow(() -> checker.requireReadable("alice", "group_group-1"));
    }

    @Test
    void rejectsGroupConversationForNonMember() {
        ConversationAccessChecker checker = new ConversationAccessChecker(new RecordingConversationManager(), new RecordingGroupManager());

        ImException ex = assertThrows(ImException.class,
                () -> checker.requireReadable("mallory", "group_group-1"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void listsConversationIdsVisibleToUser() {
        RecordingConversationManager conversationManager = new RecordingConversationManager();
        conversationManager.conversations.put("alice", List.of(
                new Conversation("single_alice_bob", "alice", ConversationType.SINGLE),
                new Conversation("", "alice", ConversationType.SINGLE),
                new Conversation(null, "alice", ConversationType.SINGLE)
        ));
        ConversationAccessChecker checker = new ConversationAccessChecker(conversationManager, new RecordingGroupManager());

        assertEquals(List.of("single_alice_bob"), checker.listReadableConversationIds("alice"));
    }

    private static final class RecordingConversationManager implements IConversationManager {
        private final Map<String, Conversation> visibleConversations = new HashMap<>();
        private final Map<String, List<Conversation>> conversations = new HashMap<>();

        @Override public List<Conversation> getConversations(String ownerUserId) { return conversations.getOrDefault(ownerUserId, List.of()); }
        @Override public Conversation getConversation(String ownerUserId, String conversationId) { return visibleConversations.get(ownerUserId + "|" + conversationId); }
        @Override public void updateOnMessage(String ownerUserId, String conversationId, Message msg, boolean isSelf) {}
        @Override public void markRead(String ownerUserId, String conversationId, long readSeq) {}
        @Override public void setPinned(String ownerUserId, String conversationId, boolean pinned) {}
        @Override public void setRecvMsgOpt(String ownerUserId, String conversationId, int recvMsgOpt) {}
        @Override public void setBurnDuration(String ownerUserId, String conversationId, int burnDuration) {}
        @Override public int getTotalUnreadCount(String userId) { return 0; }
        @Override public int getUnreadCount(String ownerUserId, String conversationId) { return 0; }
        @Override public IncrementalSyncResult<Conversation> getIncrementalConversations(String ownerUserId, long version) {
            return new IncrementalSyncResult<>(List.of(), version, false);
        }
    }

    private static final class RecordingGroupManager implements IGroupManager {
        private final Set<String> members = new HashSet<>();

        @Override public boolean isMember(String groupId, String userId) { return members.contains(groupId + "|" + userId); }
        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {}
        @Override public void disbandGroup(String groupId, String operatorId) {}
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public void quitGroup(String groupId, String userId) {}
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public com.im.api.GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { return com.im.api.GroupJoinResult.APPLY_CREATED; }
        @Override public com.im.api.GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) { return com.im.api.GroupApplyHandleResult.HANDLED; }
        @Override public List<com.im.api.GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }
        @Override public List<com.im.api.GroupMemberInformation> getMemberList(String groupId) { return List.of(); }
        @Override public Set<String> getMemberIds(String groupId) { return Set.of(); }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }
        @Override public String getRole(String groupId, String userId) { return null; }
         public com.im.api.GroupInformation getGroupInformation(String groupId) { return null; }
         public List<com.im.api.GroupInformation> searchGroups(String keyword, int limit) { return List.of(); }
    }
}
