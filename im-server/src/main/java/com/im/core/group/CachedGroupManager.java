package com.im.core.group;

import com.im.api.GroupAbstractInfo;
import com.im.api.GroupApply;
import com.im.api.GroupApplyHandleResult;
import com.im.api.GroupDisbandResult;
import com.im.api.GroupInformation;
import com.im.api.GroupJoinResult;
import com.im.api.GroupMemberInformation;
import com.im.api.IGroupManager;
import com.im.api.IncrementalSyncResult;
import com.im.core.cache.Cache;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 群资料缓存装饰器。
 *
 * <p>当前只缓存群基本资料；成员关系、禁言、角色等权限判断保持实时查询。</p>
 */
public class CachedGroupManager implements IGroupManager {

    private final IGroupManager delegate;
    private final Cache<String, GroupInformation> groupInfoCache;
    private final Cache<String, GroupMemberListSnapshot> memberListCache;
    private final Cache<String, GroupMemberIdsSnapshot> memberIdsCache;

    public CachedGroupManager(IGroupManager delegate, Cache<String, GroupInformation> groupInfoCache) {
        this(delegate, groupInfoCache, null);
    }

    public CachedGroupManager(IGroupManager delegate,
                              Cache<String, GroupInformation> groupInfoCache,
                              Cache<String, GroupMemberListSnapshot> memberListCache) {
        this(delegate, groupInfoCache, memberListCache, null);
    }

    public CachedGroupManager(IGroupManager delegate,
                              Cache<String, GroupInformation> groupInfoCache,
                              Cache<String, GroupMemberListSnapshot> memberListCache,
                              Cache<String, GroupMemberIdsSnapshot> memberIdsCache) {
        this.delegate = delegate;
        this.groupInfoCache = groupInfoCache;
        this.memberListCache = memberListCache;
        this.memberIdsCache = memberIdsCache;
    }

    @Override
    public void createGroup(String groupId, String ownerId, String groupName, String faceUrl,
                            List<String> members, int groupType, int needVerification) {
        delegate.createGroup(groupId, ownerId, groupName, faceUrl, members, groupType, needVerification);
        invalidateGroupCaches(groupId);
    }

    @Override
    public GroupDisbandResult disbandGroup(String groupId, String operatorId) {
        GroupDisbandResult result = delegate.disbandGroup(groupId, operatorId);
        invalidateGroupCaches(groupId);
        return result;
    }

    @Override
    public void setGroupInformation(String groupId, String groupName, String notification,
                                    String introduction, String faceUrl, int needVerification,
                                    int lookMemberInfo, int applyMemberFriend,
                                    String notificationUserId) {
        delegate.setGroupInformation(groupId, groupName, notification, introduction, faceUrl,
                needVerification, lookMemberInfo, applyMemberFriend, notificationUserId);
        groupInfoCache.invalidate(groupId);
    }

    @Override
    public void addMember(String groupId, String userId) {
        delegate.addMember(groupId, userId);
        invalidateGroupCaches(groupId);
    }

    @Override
    public void addMembers(String groupId, List<String> userIds) {
        delegate.addMembers(groupId, userIds);
        invalidateGroupCaches(groupId);
    }

    @Override
    public void kickMember(String groupId, String operatorId, String targetUserId) {
        delegate.kickMember(groupId, operatorId, targetUserId);
        invalidateGroupCaches(groupId);
    }

    @Override
    public boolean quitGroup(String groupId, String userId) {
        boolean removed = delegate.quitGroup(groupId, userId);
        if (removed) {
            invalidateGroupCaches(groupId);
        }
        return removed;
    }

    @Override
    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
        delegate.transferOwner(groupId, oldOwnerId, newOwnerId);
        invalidateGroupCaches(groupId);
    }

    @Override
    public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {
        delegate.setMemberRole(groupId, operatorId, targetUserId, roleLevel);
    }

    @Override
    public void muteMember(String groupId, String targetUserId, long muteEndTime) {
        delegate.muteMember(groupId, targetUserId, muteEndTime);
    }

    @Override
    public GroupJoinResult joinGroup(String groupId, String userId, String reqMsg) {
        GroupJoinResult result = delegate.joinGroup(groupId, userId, reqMsg);
        invalidateGroupCaches(groupId);
        return result;
    }

    @Override
    public GroupApplyHandleResult respondJoinRequest(String groupId, String userId, String operatorId,
                                                     String handleMsg, boolean agreed) {
        GroupApplyHandleResult result = delegate.respondJoinRequest(groupId, userId, operatorId, handleMsg, agreed);
        invalidateGroupCaches(groupId);
        return result;
    }

    @Override
    public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) {
        return delegate.getJoinRequests(groupId, onlyPending);
    }

    @Override
    public List<GroupApply> getManageableJoinRequests(String operatorId, boolean onlyPending) {
        return delegate.getManageableJoinRequests(operatorId, onlyPending);
    }

    @Override
    public List<String> getManagerIds(String groupId) {
        return delegate.getManagerIds(groupId);
    }

    @Override
    public void muteGroupAll(String groupId, String operatorId, boolean mute) {
        delegate.muteGroupAll(groupId, operatorId, mute);
    }

    @Override
    public boolean isMemberMuted(String groupId, String userId) {
        return delegate.isMemberMuted(groupId, userId);
    }

    @Override
    public void setMemberInfo(String groupId, String userId, String ex) {
        delegate.setMemberInfo(groupId, userId, ex);
    }

    @Override
    public void inviteMembers(String groupId, String operatorId, List<String> userIds) {
        delegate.inviteMembers(groupId, operatorId, userIds);
        invalidateGroupCaches(groupId);
    }

    @Override
    public GroupAbstractInfo getGroupAbstractInfo(String groupId) {
        return delegate.getGroupAbstractInfo(groupId);
    }

    @Override
    public IncrementalSyncResult<String> getIncrementalGroups(String userId, long version) {
        return delegate.getIncrementalGroups(userId, version);
    }

    @Override
    public IncrementalSyncResult<GroupMemberInformation> getIncrementalMembers(String groupId, long version) {
        return delegate.getIncrementalMembers(groupId, version);
    }

    @Override
    public List<GroupMemberInformation> getMemberList(String groupId) {
        if (memberListCache == null) {
            return delegate.getMemberList(groupId);
        }
        Optional<GroupMemberListSnapshot> cached = memberListCache.get(groupId);
        if (cached.isPresent()) {
            return cached.get().getMembers();
        }
        List<GroupMemberInformation> loaded = delegate.getMemberList(groupId);
        memberListCache.put(groupId, new GroupMemberListSnapshot(loaded));
        return loaded;
    }

    @Override
    public Set<String> getMemberIds(String groupId) {
        if (memberIdsCache == null) {
            return delegate.getMemberIds(groupId);
        }
        Optional<GroupMemberIdsSnapshot> cached = memberIdsCache.get(groupId);
        if (cached.isPresent()) {
            return cached.get().getMemberIds();
        }
        Set<String> loaded = delegate.getMemberIds(groupId);
        memberIdsCache.put(groupId, new GroupMemberIdsSnapshot(loaded));
        return loaded;
    }

    @Override
    public boolean isMember(String groupId, String userId) {
        return delegate.isMember(groupId, userId);
    }

    @Override
    public String getRole(String groupId, String userId) {
        return delegate.getRole(groupId, userId);
    }

    @Override
    public Set<String> getJoinedGroups(String userId) {
        return delegate.getJoinedGroups(userId);
    }

    @Override
    public List<GroupInformation> getJoinedGroupInformationList(String userId) {
        return delegate.getJoinedGroupInformationList(userId);
    }

    @Override
    public GroupInformation getGroupInformation(String groupId) {
        Optional<GroupInformation> cached = groupInfoCache.get(groupId);
        if (cached.isPresent()) {
            return cached.get();
        }
        GroupInformation loaded = delegate.getGroupInformation(groupId);
        if (loaded != null) {
            groupInfoCache.put(groupId, loaded);
        }
        return loaded;
    }

    @Override
    public List<GroupInformation> searchGroups(String keyword, int limit) {
        return delegate.searchGroups(keyword, limit);
    }

    private void invalidateGroupCaches(String groupId) {
        groupInfoCache.invalidate(groupId);
        if (memberListCache != null) {
            memberListCache.invalidate(groupId);
        }
        if (memberIdsCache != null) {
            memberIdsCache.invalidate(groupId);
        }
    }
}
