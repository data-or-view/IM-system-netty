package com.im.core.group;

import com.im.api.*;
import com.im.api.cache.ICache;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地内存群组管理器（单机开发/测试用）。
 *
 * 节点重启后数据丢失——生产环境请换 DB 实现。
 *
 * 可选的缓存层（SafeCache 包裹，任何异常降级到内存数据源）。
 */
public class LocalGroupManager implements IGroupManager {

    private static final Logger log = LoggerFactory.getLogger(LocalGroupManager.class);
    private static final long GROUP_CACHE_TTL = 300; // 5分钟
    private static final long MEMBER_CACHE_TTL = 120; // 2分钟

    /** groupId → 群信息 */
    private final ConcurrentMap<String, GroupInformation> groupInfos = new ConcurrentHashMap<>();

    /** groupId → 成员集合 */
    private final ConcurrentMap<String, CopyOnWriteArraySet<String>> groups = new ConcurrentHashMap<>();

    /** userId → 加入的群集合 */
    private final ConcurrentMap<String, CopyOnWriteArraySet<String>> userGroups = new ConcurrentHashMap<>();

    /** groupId → 加群申请列表 */
    private final ConcurrentMap<String, CopyOnWriteArrayList<GroupApply>> joinRequests = new ConcurrentHashMap<>();

    // ── 缓存层（SafeCache 包裹，异常不传播） ──
    private final ICache<String, GroupInformation> groupInfoCache;
    private final ICache<String, List<String>> memberListCache;

    public LocalGroupManager() {
        this(null, null);
    }

    public LocalGroupManager(ICache<String, GroupInformation> groupInfoCache,
                             ICache<String, List<String>> memberListCache) {
        this.groupInfoCache = groupInfoCache != null
                ? new SafeCache<>(groupInfoCache, "LocalGroupManager.Info")
                : null;
        this.memberListCache = memberListCache != null
                ? new SafeCache<>(memberListCache, "LocalGroupManager.Members")
                : null;
    }

    @Override
    public void createGroup(String groupId, String ownerId, String groupName, List<String> members) {
        if (groups.containsKey(groupId)) {
            log.warn("Group already exists: {}", groupId);
            return;
        }
        CopyOnWriteArraySet<String> memberSet = new CopyOnWriteArraySet<>();
        memberSet.add(ownerId);
        memberSet.addAll(members);
        groups.put(groupId, memberSet);
        groupInfos.put(groupId, new GroupInformation(groupId, groupName, ownerId));

        addUserGroupIndex(ownerId, groupId);
        members.forEach(m -> addUserGroupIndex(m, groupId));
        log.info("Group created: id={}, owner={}, members={}", groupId, ownerId, memberSet.size());
    }

    @Override
    public void disbandGroup(String groupId, String operatorId) {
        CopyOnWriteArraySet<String> removed = groups.remove(groupId);
        groupInfos.remove(groupId);
        if (removed != null) {
            removed.forEach(userId -> {
                CopyOnWriteArraySet<String> joined = userGroups.get(userId);
                if (joined != null) joined.remove(groupId);
            });
            log.info("Group disbanded: {}", groupId);
        }
    }

    @Override
    public void setGroupInformation(String groupId, String groupName, String notification, String faceUrl) {
        GroupInformation info = groupInfos.get(groupId);
        if (info != null) {
            if (groupName != null) info.setGroupName(groupName);
            if (notification != null) info.setNotification(notification);
            if (faceUrl != null) info.setFaceUrl(faceUrl);
            invalidateGroupCache(groupId);
        }
    }

    @Override
    public void addMember(String groupId, String userId) {
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        if (memberSet == null) {
            log.warn("Group not found: {}", groupId);
            return;
        }
        if (memberSet.add(userId)) {
            addUserGroupIndex(userId, groupId);
            GroupInformation info = groupInfos.get(groupId);
            if (info != null) info.setMemberCount(memberSet.size());
            invalidateMemberCache(groupId);
        }
    }

    @Override
    public void addMembers(String groupId, List<String> userIds) {
        userIds.forEach(uid -> addMember(groupId, uid));
    }

    @Override
    public void kickMember(String groupId, String operatorId, String targetUserId) {
        removeMember(groupId, targetUserId);
    }

    @Override
    public void quitGroup(String groupId, String userId) {
        removeMember(groupId, userId);
    }

    private void removeMember(String groupId, String userId) {
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        if (memberSet != null && memberSet.remove(userId)) {
            CopyOnWriteArraySet<String> joined = userGroups.get(userId);
            if (joined != null) joined.remove(groupId);
            GroupInformation info = groupInfos.get(groupId);
            if (info != null) info.setMemberCount(memberSet.size());
            log.info("Member removed: userId={} from group={}", userId, groupId);
            invalidateMemberCache(groupId);
        }
    }

    @Override
    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
        GroupInformation info = groupInfos.get(groupId);
        if (info != null) {
            info.setOwnerUserId(newOwnerId);
            invalidateGroupCache(groupId);
            log.info("Owner transferred: group={}, from={}, to={}", groupId, oldOwnerId, newOwnerId);
        }
    }

    @Override
    public void joinGroup(String groupId, String userId, String reqMsg) {
        GroupApply apply = new GroupApply();
        apply.setGroupId(groupId);
        apply.setUserId(userId);
        apply.setReqMsg(reqMsg);
        apply.setCreateTime(System.currentTimeMillis());
        joinRequests.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>()).add(apply);
        log.info("Join request: userId={} -> group={}", userId, groupId);
    }

    @Override
    public void respondJoinRequest(String groupId, String userId, String operatorId,
                                   String handleMsg, boolean agreed) {
        CopyOnWriteArrayList<GroupApply> requests = joinRequests.get(groupId);
        if (requests == null) return;
        for (GroupApply apply : requests) {
            if (userId.equals(apply.getUserId()) && apply.getHandleResult() == 0) {
                apply.setHandleResult(agreed ? 1 : 2);
                apply.setHandleUserId(operatorId);
                apply.setHandleMsg(handleMsg);
                apply.setHandleTime(System.currentTimeMillis());
                if (agreed) addMember(groupId, userId);
                break;
            }
        }
    }

    @Override
    public List<GroupApply> getJoinRequests(String groupId) {
        CopyOnWriteArrayList<GroupApply> requests = joinRequests.get(groupId);
        return requests != null ? requests : List.of();
    }

    @Override
    public Set<String> getMemberIds(String groupId) {
        if (memberListCache != null) {
            List<String> cached = memberListCache.getOrLoad(
                    memberListKey(groupId),
                    () -> {
                        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
                        return memberSet != null ? List.copyOf(memberSet) : List.of();
                    },
                    120);
            return Set.copyOf(cached);
        }
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        return memberSet != null ? memberSet : Set.of();
    }

    @Override
    public boolean isMember(String groupId, String userId) {
        // isMember 优先走 memberListCache（批量读取缓存一次）
        if (memberListCache != null) {
            List<String> members = memberListCache.getOrLoad(
                    memberListKey(groupId),
                    () -> {
                        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
                        return memberSet != null ? List.copyOf(memberSet) : List.of();
                    },
                    120);
            return members.contains(userId);
        }
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        return memberSet != null && memberSet.contains(userId);
    }

    @Override
    public Set<String> getJoinedGroups(String userId) {
        CopyOnWriteArraySet<String> joined = userGroups.get(userId);
        return joined != null ? joined : Set.of();
    }

    @Override
    public GroupInformation getGroupInformation(String groupId) {
        if (groupInfoCache != null) {
            return groupInfoCache.getOrLoad(groupId, () -> {
                GroupInformation info = groupInfos.get(groupId);
                if (info == null) {
                    throw new ImException(ImErrorCode.NOT_FOUND, "Group not found: " + groupId);
                }
                return info;
            }, GROUP_CACHE_TTL);
        }
        GroupInformation info = groupInfos.get(groupId);
        if (info == null) {
            throw new ImException(ImErrorCode.NOT_FOUND, "Group not found: " + groupId);
        }
        return info;
    }

    @Override
    public String getRole(String groupId, String userId) {
        GroupInformation info = groupInfos.get(groupId);
        if (info == null) return null;
        if (info.getOwnerUserId().equals(userId)) return "owner";
        if (isMember(groupId, userId)) return "member";
        return null;
    }

    // ── 缓存失效（SafeCache 保证 delete 不抛异常） ──

    private void invalidateGroupCache(String groupId) {
        if (groupInfoCache != null) groupInfoCache.delete(groupId);
    }

    private void invalidateMemberCache(String groupId) {
        if (memberListCache != null) memberListCache.delete(memberListKey(groupId));
    }

    private static String memberListKey(String groupId) {
        return "members:" + groupId;
    }

    private void addUserGroupIndex(String userId, String groupId) {
        userGroups.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(groupId);
    }
}
