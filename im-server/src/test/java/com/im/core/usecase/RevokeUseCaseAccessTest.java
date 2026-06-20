package com.im.core.usecase;

import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupDisbandResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.IConversationAccessChecker;
import com.im.api.IGroupManager;
import com.im.api.IMessageStore;
import com.im.api.Message;
import com.im.api.SearchMessagesParam;
import com.im.api.SearchMessagesResult;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevokeUseCaseAccessTest {

    @Test
    void rejectsUnreadableConversationBeforeUpdatingMessage() {
        RecordingMessageStore store = new RecordingMessageStore();
        RevokeUseCase useCase = new RevokeUseCase(store, new StubGroupManager(),
                new RejectingAccessChecker());

        ImException ex = assertThrows(ImException.class,
                () -> useCase.execute("mallory", "single_alice_bob", 7, null));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(0, store.revokeCalls);
    }

    @Test
    void rejectsMismatchedGroupIdAndConversationId() {
        RecordingMessageStore store = new RecordingMessageStore();
        RevokeUseCase useCase = new RevokeUseCase(store, new StubGroupManager(),
                new AllowingAccessChecker());

        ImException ex = assertThrows(ImException.class,
                () -> useCase.execute("owner", "group_g1", 7, "g2"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(0, store.revokeCalls);
    }

    @Test
    void memberRevokeUsesOrdinaryRoleSoStoreCanRestrictToOwnMessages() {
        RecordingMessageStore store = new RecordingMessageStore();
        RevokeUseCase useCase = new RevokeUseCase(store, new StubGroupManager("member"),
                new AllowingAccessChecker());

        useCase.execute("alice", "group_g1", 7, "g1");

        assertEquals(1, store.revokeCalls);
        assertEquals(0, store.lastRole);
        assertEquals("alice", store.lastRevokerId);
    }

    @Test
    void adminRevokeCarriesAdminRoleForStoreSideAuthorization() {
        RecordingMessageStore store = new RecordingMessageStore();
        RevokeUseCase useCase = new RevokeUseCase(store, new StubGroupManager("admin"),
                new AllowingAccessChecker());

        useCase.execute("admin", "group_g1", 7, "g1");

        assertEquals(1, store.revokeCalls);
        assertTrue(store.lastRole >= 100);
    }

    private static final class RecordingMessageStore implements IMessageStore {
        int revokeCalls;
        int lastRole;
        String lastRevokerId;

        @Override public void save(Message msg) {}
        @Override public List<Message> pullOffline(String userId, int limit) { return List.of(); }
        @Override public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) { return List.of(); }
        @Override public void markDelivered(String userId, List<String> msgIds) {}
        @Override public SearchMessagesResult searchMessages(SearchMessagesParam param) { return SearchMessagesResult.empty(); }

        @Override
        public boolean revokeMessage(String conversationId, long seq, String revokerId, int role, String nickname) {
            revokeCalls++;
            lastRole = role;
            lastRevokerId = revokerId;
            return true;
        }
    }

    private static final class AllowingAccessChecker implements IConversationAccessChecker {
        @Override public void requireReadable(String userId, String conversationId) {}
        @Override public List<String> listReadableConversationIds(String userId) { return List.of(conversationId(userId)); }
        private String conversationId(String userId) { return "single_" + userId + "_other"; }
    }

    private static final class RejectingAccessChecker implements IConversationAccessChecker {
        @Override public void requireReadable(String userId, String conversationId) {
            throw new ImException(ImErrorCode.FORBIDDEN, "conversation not readable");
        }
        @Override public List<String> listReadableConversationIds(String userId) { return List.of(); }
    }

    private static final class StubGroupManager implements IGroupManager {
        private final String role;

        StubGroupManager() {
            this("member");
        }

        StubGroupManager(String role) {
            this.role = role;
        }

        @Override public boolean isMember(String groupId, String userId) { return true; }
        @Override public String getRole(String groupId, String userId) { return role; }
        @Override public Set<String> getMemberIds(String groupId) { return Set.of("alice", "admin", "bob"); }
        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {}
        @Override public GroupDisbandResult disbandGroup(String groupId, String operatorId) { throw new UnsupportedOperationException(); }
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public boolean quitGroup(String groupId, String userId) { return false; }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { return GroupJoinResult.APPLY_CREATED; }
        @Override public GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) { return GroupApplyHandleResult.HANDLED; }
        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }
        @Override public List<GroupMemberInformation> getMemberList(String groupId) { return List.of(); }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }
        @Override public GroupInformation getGroupInformation(String groupId) { return null; }
        @Override public List<GroupInformation> searchGroups(String keyword, int limit) { return List.of(); }
    }
}
