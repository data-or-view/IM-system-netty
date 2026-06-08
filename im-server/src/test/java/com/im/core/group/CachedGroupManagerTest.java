package com.im.core.group;

import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachedGroupManagerTest {

    @Test
    void getGroupInformationUsesCacheUntilGroupIsUpdated() {
        RecordingGroupManager delegate = new RecordingGroupManager();
        delegate.group = group("g1", "old name");
        CachedGroupManager manager = new CachedGroupManager(delegate,
                new SafeCache<>(new ConcurrentHashCache<>(), "group-profile-test"));

        assertEquals("old name", manager.getGroupInformation("g1").getGroupName());
        delegate.group = group("g1", "stale source");
        assertEquals("old name", manager.getGroupInformation("g1").getGroupName());
        assertEquals(1, delegate.infoCalls);

        delegate.group = group("g1", "new name");
        manager.setGroupInformation("g1", "new name", null, null, null, -1, -1, -1, "u1");

        assertEquals("new name", manager.getGroupInformation("g1").getGroupName());
        assertEquals(2, delegate.infoCalls);
    }

    @Test
    void disbandInvalidatesGroupInformationCache() {
        RecordingGroupManager delegate = new RecordingGroupManager();
        delegate.group = group("g1", "active");
        CachedGroupManager manager = new CachedGroupManager(delegate,
                new SafeCache<>(new ConcurrentHashCache<>(), "group-profile-test"));

        assertEquals("active", manager.getGroupInformation("g1").getGroupName());
        delegate.group = null;
        manager.disbandGroup("g1", "owner");

        assertEquals(null, manager.getGroupInformation("g1"));
        assertEquals(2, delegate.infoCalls);
    }

    @Test
    void getMemberListUsesCacheUntilMembershipChanges() {
        RecordingGroupManager delegate = new RecordingGroupManager();
        delegate.members = List.of(member("g1", "u1"));
        CachedGroupManager manager = new CachedGroupManager(delegate,
                new SafeCache<>(new ConcurrentHashCache<>(), "group-profile-test"),
                new SafeCache<>(new ConcurrentHashCache<>(), "group-member-list-test"));

        assertEquals(List.of("u1"), manager.getMemberList("g1").stream().map(GroupMemberInformation::getUserId).toList());
        delegate.members = List.of(member("g1", "u1"), member("g1", "u2"));
        assertEquals(List.of("u1"), manager.getMemberList("g1").stream().map(GroupMemberInformation::getUserId).toList());
        assertEquals(1, delegate.memberListCalls);

        manager.addMember("g1", "u2");

        assertEquals(List.of("u1", "u2"), manager.getMemberList("g1").stream().map(GroupMemberInformation::getUserId).toList());
        assertEquals(2, delegate.memberListCalls);
    }

    @Test
    void getMemberIdsUsesCacheUntilMembershipChanges() {
        RecordingGroupManager delegate = new RecordingGroupManager();
        delegate.memberIds = Set.of("u1");
        CachedGroupManager manager = new CachedGroupManager(delegate,
                new SafeCache<>(new ConcurrentHashCache<>(), "group-profile-test"),
                new SafeCache<>(new ConcurrentHashCache<>(), "group-member-list-test"),
                new SafeCache<>(new ConcurrentHashCache<>(), "group-member-ids-test"));

        assertEquals(Set.of("u1"), manager.getMemberIds("g1"));
        delegate.memberIds = Set.of("u1", "u2");
        assertEquals(Set.of("u1"), manager.getMemberIds("g1"));
        assertEquals(1, delegate.memberIdsCalls);

        manager.kickMember("g1", "owner", "u2");

        assertEquals(Set.of("u1", "u2"), manager.getMemberIds("g1"));
        assertEquals(2, delegate.memberIdsCalls);
    }

    @Test
    void quitGroupOnlyInvalidatesCacheWhenMembershipChanged() {
        RecordingGroupManager delegate = new RecordingGroupManager();
        delegate.memberIds = Set.of("u1");
        delegate.quitResult = false;
        CachedGroupManager manager = new CachedGroupManager(delegate,
                new SafeCache<>(new ConcurrentHashCache<>(), "group-profile-test"),
                new SafeCache<>(new ConcurrentHashCache<>(), "group-member-list-test"),
                new SafeCache<>(new ConcurrentHashCache<>(), "group-member-ids-test"));

        assertEquals(Set.of("u1"), manager.getMemberIds("g1"));
        delegate.memberIds = Set.of("u2");

        manager.quitGroup("g1", "u1");

        assertEquals(Set.of("u1"), manager.getMemberIds("g1"));
        assertEquals(1, delegate.memberIdsCalls);
    }

    private static GroupInformation group(String groupId, String name) {
        GroupInformation info = new GroupInformation();
        info.setGroupId(groupId);
        info.setGroupName(name);
        return info;
    }

    private static GroupMemberInformation member(String groupId, String userId) {
        GroupMemberInformation info = new GroupMemberInformation();
        info.setGroupId(groupId);
        info.setUserId(userId);
        return info;
    }

    private static final class RecordingGroupManager implements IGroupManager {
        private GroupInformation group;
        private List<GroupMemberInformation> members = List.of();
        private Set<String> memberIds = Set.of();
        private int infoCalls;
        private int memberListCalls;
        private int memberIdsCalls;
        private boolean quitResult = true;

        @Override public void createGroup(String groupId, String ownerId, String groupName, String faceUrl, List<String> members, int groupType, int needVerification) {}
        @Override public void disbandGroup(String groupId, String operatorId) {}
        @Override public void setGroupInformation(String groupId, String groupName, String notification, String introduction, String faceUrl, int needVerification, int lookMemberInfo, int applyMemberFriend, String notificationUserId) {}
        @Override public void addMember(String groupId, String userId) {}
        @Override public void addMembers(String groupId, List<String> userIds) {}
        @Override public void kickMember(String groupId, String operatorId, String targetUserId) {}
        @Override public boolean quitGroup(String groupId, String userId) { return quitResult; }
        @Override public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {}
        @Override public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {}
        @Override public void muteMember(String groupId, String targetUserId, long muteEndTime) {}
        @Override public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) { return GroupJoinResult.JOINED; }
        @Override public GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId, String handleMsg, boolean agreed) { return GroupApplyHandleResult.HANDLED; }
        @Override public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) { return List.of(); }
        @Override
        public List<GroupMemberInformation> getMemberList(String groupId) {
            memberListCalls++;
            return members;
        }
        @Override
        public Set<String> getMemberIds(String groupId) {
            memberIdsCalls++;
            return memberIds;
        }
        @Override public boolean isMember(String groupId, String userId) { return false; }
        @Override public String getRole(String groupId, String userId) { return null; }
        @Override public Set<String> getJoinedGroups(String userId) { return Set.of(); }

        @Override
        public GroupInformation getGroupInformation(String groupId) {
            infoCalls++;
            return group;
        }

        @Override public List<GroupInformation> searchGroups(String keyword, int limit) { return List.of(); }
    }
}
