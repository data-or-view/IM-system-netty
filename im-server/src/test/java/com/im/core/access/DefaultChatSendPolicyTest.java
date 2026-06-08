package com.im.core.access;

import com.im.api.FriendApply;
import com.im.api.FriendInformation;
import com.im.api.GroupApply;
import com.im.api.GroupInformation;
import com.im.api.GroupMemberInformation;
import com.im.api.GroupStatus;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IUserManager;
import com.im.api.IncrementalSyncResult;
import com.im.api.UserInformation;
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

class DefaultChatSendPolicyTest {

    @Test
    void rejectsSingleChatWhenTargetUserDoesNotExist() {
        FakeUserManager userManager = new FakeUserManager();
        userManager.users.put("alice", new UserInformation("alice", "Alice"));
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(userManager, new FakeFriendManager(), new FakeGroupManager(), true);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendSingle("alice", "bob"));

        assertEquals(ImErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void rejectsSingleChatWhenTargetBlockedSender() {
        FakeUserManager userManager = users("alice", "bob");
        FakeFriendManager friendManager = new FakeFriendManager();
        friendManager.blocked.add("alice|bob");
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(userManager, friendManager, new FakeGroupManager(), false);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendSingle("alice", "bob"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void rejectsSingleChatWhenFriendRequiredAndUsersAreNotFriends() {
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(users("alice", "bob"), new FakeFriendManager(), new FakeGroupManager(), true);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendSingle("alice", "bob"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("对方已删除你，无法发送消息", ex.getDetail());
    }

    @Test
    void allowsSingleChatWhenFriendRequiredAndUsersAreFriends() {
        FakeFriendManager friendManager = new FakeFriendManager();
        friendManager.friends.add("alice|bob");
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(users("alice", "bob"), friendManager, new FakeGroupManager(), true);

        assertDoesNotThrow(() -> policy.requireCanSendSingle("alice", "bob"));
    }

    @Test
    void rejectsGroupChatWhenSenderIsNotMember() {
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(users("alice"), new FakeFriendManager(), new FakeGroupManager(), false);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendGroup("alice", "group-1"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void rejectsGroupChatWhenGroupWasDisbanded() {
        FakeGroupManager groupManager = new FakeGroupManager();
        groupManager.statuses.put("group-1", GroupStatus.DISBANDED);
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(users("alice"), new FakeFriendManager(), groupManager, false);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendGroup("alice", "group-1"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("群聊已解散，无法发送消息", ex.getDetail());
    }

    @Test
    void rejectsGroupChatWhenSenderIsMuted() {
        FakeGroupManager groupManager = new FakeGroupManager();
        groupManager.members.add("group-1|alice");
        groupManager.muted.add("group-1|alice");
        DefaultChatSendPolicy policy = new DefaultChatSendPolicy(users("alice"), new FakeFriendManager(), groupManager, false);

        ImException ex = assertThrows(ImException.class,
                () -> policy.requireCanSendGroup("alice", "group-1"));

        assertEquals(ImErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    private static FakeUserManager users(String... userIds) {
        FakeUserManager manager = new FakeUserManager();
        for (String userId : userIds) {
            manager.users.put(userId, new UserInformation(userId, userId));
        }
        return manager;
    }

    private static final class FakeUserManager implements IUserManager {
        private final Map<String, UserInformation> users = new HashMap<>();

        @Override public UserInformation getUserInformation(String userId) { return users.get(userId); }
        @Override public void register(String userId, String nickname, String faceUrl, String ex) {}
        @Override public List<UserInformation> getUsersInfo(List<String> userIds) { return List.of(); }
        @Override public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) { return Map.of(); }
        @Override public void updateUserInformation(String userId, String nickname, String faceUrl, String ex, int globalRecvMsgOpt) {}
        @Override public List<UserInformation> searchUsers(String keyword, int limit) { return List.of(); }
    }

    private static final class FakeFriendManager implements IFriendManager {
        private final Set<String> friends = new HashSet<>();
        private final Set<String> blocked = new HashSet<>();

        @Override public boolean isFriend(String userIdA, String userIdB) { return friends.contains(userIdA + "|" + userIdB) || friends.contains(userIdB + "|" + userIdA); }
        @Override public boolean isBlocked(String fromUserId, String toUserId) { return blocked.contains(fromUserId + "|" + toUserId); }
        @Override public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {}
        @Override public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {}
        @Override public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) { return List.of(); }
        @Override public boolean deleteFriend(String ownerUserId, String friendUserId) { return true; }
        @Override public List<FriendInformation> getFriendList(String userId) { return List.of(); }
        @Override public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {}
        @Override public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {}
        @Override public void addBlack(String ownerUserId, String blockedUserId) {}
        @Override public void removeBlack(String ownerUserId, String blockedUserId) {}
        @Override public List<String> getBlackList(String userId) { return List.of(); }
    }

    private static final class FakeGroupManager implements IGroupManager {
        private final Set<String> members = new HashSet<>();
        private final Set<String> muted = new HashSet<>();
        private final Map<String, GroupStatus> statuses = new HashMap<>();

        @Override public boolean isMember(String groupId, String userId) { return members.contains(groupId + "|" + userId); }
        @Override public GroupStatus getGroupStatus(String groupId) { return statuses.get(groupId); }
        @Override public boolean isMemberMuted(String groupId, String userId) { return muted.contains(groupId + "|" + userId); }
        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {}
        @Override public com.im.api.GroupDisbandResult disbandGroup(String groupId, String operatorId) {
            return new com.im.api.GroupDisbandResult(groupId, operatorId, groupId, List.of());
        }
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public boolean quitGroup(String groupId, String userId) { return true; }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public com.im.api.GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { return com.im.api.GroupJoinResult.APPLY_CREATED; }
        @Override public com.im.api.GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) { return com.im.api.GroupApplyHandleResult.HANDLED; }
        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }
        @Override public List<GroupMemberInformation> getMemberList(String groupId) { return List.of(); }
        @Override public Set<String> getMemberIds(String groupId) { return Set.of(); }
        @Override public String getRole(String groupId, String userId) { return null; }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }
        @Override public GroupInformation getGroupInformation(String groupId) { return null; }
        @Override public List<GroupInformation> searchGroups(String keyword, int limit) { return List.of(); }
    }
}
