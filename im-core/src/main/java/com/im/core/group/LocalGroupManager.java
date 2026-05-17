package com.im.core.group;

import com.im.api.*;
import com.im.core.cache.Cache;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import com.im.core.sync.LocalIncrementalSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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

    /** groupId → 成员详情（userId → 角色等信息） */
    private final ConcurrentMap<String, ConcurrentMap<String, GroupMemberInformation>> memberInfos = new ConcurrentHashMap<>();

    /** groupId → 加群申请列表 */
    private final ConcurrentMap<String, CopyOnWriteArrayList<GroupApply>> joinRequests = new ConcurrentHashMap<>();

    // ── 缓存层（SafeCache 包裹，异常不传播） ──
    private final Cache<String, GroupInformation> groupInfoCache;
    private final Cache<String, List<String>> memberListCache;

    /** 增量同步追踪 */
    private final LocalIncrementalSync sync;

    public LocalGroupManager() {
        this(null, null, new LocalIncrementalSync());
    }

    public LocalGroupManager(Cache<String, GroupInformation> groupInfoCache,
                             Cache<String, List<String>> memberListCache) {
        this(groupInfoCache, memberListCache, new LocalIncrementalSync());
    }

    public LocalGroupManager(Cache<String, GroupInformation> groupInfoCache,
                             Cache<String, List<String>> memberListCache,
                             LocalIncrementalSync sync) {
        this.groupInfoCache = groupInfoCache != null
                ? new SafeCache<>(groupInfoCache, "LocalGroupManager.Info")
                : null;
        this.memberListCache = memberListCache != null
                ? new SafeCache<>(memberListCache, "LocalGroupManager.Members")
                : null;
        this.sync = sync;
    }

    @Override
    public void createGroup(String groupId, String ownerId, String groupName, String faceUrl,
                            List<String> members, int groupType, int needVerification) {
        if (groups.containsKey(groupId)) {
            log.warn("Group already exists: {}", groupId);
            return;
        }
        CopyOnWriteArraySet<String> memberSet = new CopyOnWriteArraySet<>();
        memberSet.add(ownerId);
        memberSet.addAll(members);
        groups.put(groupId, memberSet);

        GroupInformation info = new GroupInformation(groupId, groupName, ownerId);
        info.setFaceUrl(faceUrl);
        info.setGroupType(groupType);
        info.setNeedVerification(needVerification);
        groupInfos.put(groupId, info);

        // 初始化成员角色
        ConcurrentMap<String, GroupMemberInformation> memberMap = new ConcurrentHashMap<>();
        memberMap.put(ownerId, createMemberInfo(groupId, ownerId, 200));
        for (String m : members) {
            memberMap.put(m, createMemberInfo(groupId, m, 1));
        }
        memberInfos.put(groupId, memberMap);

        addUserGroupIndex(ownerId, groupId);
        sync.recordChange(ownerId, "group", groupId, "insert");
        sync.recordChange(groupId, "member", ownerId, "insert");
        for (String m : members) {
            addUserGroupIndex(m, groupId);
            sync.recordChange(m, "group", groupId, "insert");
            sync.recordChange(groupId, "member", m, "insert");
        }

        log.info("Group created: id={}, owner={}, members={}, type={}",
                groupId, ownerId, memberSet.size(), groupType);
    }

    @Override
    public void disbandGroup(String groupId, String operatorId) {
        CopyOnWriteArraySet<String> removed = groups.remove(groupId);
        groupInfos.remove(groupId);
        memberInfos.remove(groupId);
        if (removed != null) {
            removed.forEach(userId -> {
                CopyOnWriteArraySet<String> joined = userGroups.get(userId);
                if (joined != null) joined.remove(groupId);
                sync.recordChange(userId, "group", groupId, "delete");
                sync.recordChange(groupId, "member", userId, "delete");
            });
            log.info("Group disbanded: {}", groupId);
        }
    }

    @Override
    public void setGroupInformation(String groupId, String groupName, String notification,
                                    String introduction, String faceUrl, int needVerification,
                                    int lookMemberInfo, int applyMemberFriend,
                                    String notificationUserId) {
        GroupInformation info = groupInfos.get(groupId);
        if (info != null) {
            if (groupName != null) info.setGroupName(groupName);
            if (notification != null) info.setNotification(notification);
            if (introduction != null) info.setIntroduction(introduction);
            if (faceUrl != null) info.setFaceUrl(faceUrl);
            if (needVerification >= 0) info.setNeedVerification(needVerification);
            if (lookMemberInfo >= 0) info.setLookMemberInfo(lookMemberInfo);
            if (applyMemberFriend >= 0) info.setApplyMemberFriend(applyMemberFriend);
            if (notificationUserId != null) {
                info.setNotificationUserId(notificationUserId);
                info.setNotificationUpdateTime(System.currentTimeMillis());
            }
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
            memberInfos.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                    .put(userId, createMemberInfo(groupId, userId, 1));
            GroupInformation info = groupInfos.get(groupId);
            if (info != null) info.setMemberCount(memberSet.size());
            invalidateMemberCache(groupId);
            sync.recordChange(userId, "group", groupId, "insert");
            sync.recordChange(groupId, "member", userId, "insert");
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

    @Override
    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId) {
        GroupInformation info = groupInfos.get(groupId);
        if (info != null) {
            info.setOwnerUserId(newOwnerId);
            setMemberRoleLocal(groupId, oldOwnerId, 1);
            setMemberRoleLocal(groupId, newOwnerId, 200);
            invalidateGroupCache(groupId);
            sync.recordChange(groupId, "member", oldOwnerId, "update");
            sync.recordChange(groupId, "member", newOwnerId, "update");
            log.info("Owner transferred: group={}, from={}, to={}", groupId, oldOwnerId, newOwnerId);
        }
    }

    @Override
    public void setMemberRole(String groupId, String operatorId, String targetUserId, int roleLevel) {
        setMemberRoleLocal(groupId, targetUserId, roleLevel);
        sync.recordChange(groupId, "member", targetUserId, "update");
        log.info("Member role set: group={}, user={}, role={}", groupId, targetUserId, roleLevel);
    }

    @Override
    public void muteMember(String groupId, String targetUserId, long muteEndTime) {
        ConcurrentMap<String, GroupMemberInformation> memberMap = memberInfos.get(groupId);
        if (memberMap != null) {
            GroupMemberInformation member = memberMap.get(targetUserId);
            if (member != null) {
                member.setMuteEndTime(muteEndTime);
                sync.recordChange(groupId, "member", targetUserId, "update");
                log.info("Member mute: group={}, user={}, muteEnd={}", groupId, targetUserId, muteEndTime);
            }
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
                apply.setHandlerUserId(operatorId);
                apply.setHandledMsg(handleMsg);
                apply.setHandledTime(System.currentTimeMillis());
                if (agreed) addMember(groupId, userId);
                break;
            }
        }
    }

    @Override
    public List<GroupApply> getJoinRequests(String groupId, boolean onlyPending) {
        CopyOnWriteArrayList<GroupApply> requests = joinRequests.get(groupId);
        if (requests == null) return List.of();
        if (onlyPending) {
            return requests.stream()
                    .filter(a -> a.getHandleResult() == 0)
                    .collect(Collectors.toList());
        }
        return List.copyOf(requests);
    }

    @Override
    public List<GroupMemberInformation> getMemberList(String groupId) {
        ConcurrentMap<String, GroupMemberInformation> memberMap = memberInfos.get(groupId);
        if (memberMap == null) return List.of();
        return memberMap.values().stream()
                .sorted((a, b) -> Integer.compare(b.getRoleLevel(), a.getRoleLevel()))
                .collect(Collectors.toList());
    }

    @Override
    public Set<String> getMemberIds(String groupId) {
        if (memberListCache != null) {
            List<String> cached = memberListCache.get(memberListKey(groupId))
                    .orElseGet(() -> {
                        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
                        List<String> list = memberSet != null ? List.copyOf(memberSet) : List.of();
                        memberListCache.put(memberListKey(groupId), list);
                        return list;
                    });
            return Set.copyOf(cached);
        }
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        return memberSet != null ? memberSet : Set.of();
    }

    @Override
    public boolean isMember(String groupId, String userId) {
        if (memberListCache != null) {
            List<String> members = memberListCache.get(memberListKey(groupId))
                    .orElseGet(() -> {
                        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
                        List<String> list = memberSet != null ? List.copyOf(memberSet) : List.of();
                        memberListCache.put(memberListKey(groupId), list);
                        return list;
                    });
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
            return groupInfoCache.get(groupId).orElseGet(() -> {
                GroupInformation info = groupInfos.get(groupId);
                if (info == null) {
                    throw new ImException(ImErrorCode.NOT_FOUND, "Group not found: " + groupId);
                }
                groupInfoCache.put(groupId, info);
                return info;
            });
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
        ConcurrentMap<String, GroupMemberInformation> memberMap = memberInfos.get(groupId);
        if (memberMap != null) {
            GroupMemberInformation member = memberMap.get(userId);
            if (member != null) {
                if (member.getRoleLevel() >= 100) return "admin";
                return "member";
            }
        }
        if (isMember(groupId, userId)) return "member";
        return null;
    }

    @Override
    public IncrementalSyncResult<String> getIncrementalGroups(String userId, long version) {
        return sync.getChangesAsIds(userId, "group", version);
    }

    @Override
    public IncrementalSyncResult<GroupMemberInformation> getIncrementalMembers(String groupId, long version) {
        return sync.getChanges(groupId, "member", version,
                uid -> {
                    // 非删除：从当前数据构建 GroupMemberInformation
                    ConcurrentMap<String, GroupMemberInformation> memberMap = memberInfos.get(groupId);
                    return memberMap != null ? memberMap.get(uid) : null;
                },
                uid -> {
                    GroupMemberInformation gmi = new GroupMemberInformation();
                    gmi.setGroupId(groupId);
                    gmi.setUserId(uid);
                    gmi.setRoleLevel(-1); // deleted marker
                    return gmi;
                });
    }

    // ── 内部方法 ──

    private GroupMemberInformation createMemberInfo(String groupId, String userId, int roleLevel) {
        GroupMemberInformation info = new GroupMemberInformation();
        info.setGroupId(groupId);
        info.setUserId(userId);
        info.setRoleLevel(roleLevel);
        info.setJoinedAt(System.currentTimeMillis());
        return info;
    }

    private void setMemberRoleLocal(String groupId, String userId, int roleLevel) {
        ConcurrentMap<String, GroupMemberInformation> memberMap =
                memberInfos.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        GroupMemberInformation member = memberMap.get(userId);
        if (member != null) {
            member.setRoleLevel(roleLevel);
        } else {
            memberMap.put(userId, createMemberInfo(groupId, userId, roleLevel));
        }
    }

    private void removeMember(String groupId, String userId) {
        CopyOnWriteArraySet<String> memberSet = groups.get(groupId);
        if (memberSet != null && memberSet.remove(userId)) {
            CopyOnWriteArraySet<String> joined = userGroups.get(userId);
            if (joined != null) joined.remove(groupId);
            ConcurrentMap<String, GroupMemberInformation> memberMap = memberInfos.get(groupId);
            if (memberMap != null) memberMap.remove(userId);
            GroupInformation info = groupInfos.get(groupId);
            if (info != null) info.setMemberCount(memberSet.size());
            invalidateMemberCache(groupId);
            sync.recordChange(userId, "group", groupId, "delete");
            sync.recordChange(groupId, "member", userId, "delete");
            log.info("Member removed: userId={} from group={}", userId, groupId);
        }
    }

    private void syncAllMembers(String groupId, List<String> memberIds, String action) {
        for (String uid : memberIds) {
            sync.recordChange(groupId, "member", uid, action);
        }
    }

    // ── 缓存失效（SafeCache 保证 delete 不抛异常） ──

    private void invalidateGroupCache(String groupId) {
        if (groupInfoCache != null) groupInfoCache.invalidate(groupId);
    }

    private void invalidateMemberCache(String groupId) {
        if (memberListCache != null) memberListCache.invalidate(memberListKey(groupId));
    }

    private static String memberListKey(String groupId) {
        return "members:" + groupId;
    }

    private void addUserGroupIndex(String userId, String groupId) {
        userGroups.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(groupId);
    }

    @Override
    public List<GroupInformation> searchGroups(String keyword, int limit) {
        return groupInfos.values().stream()
                .filter(info -> info.getGroupName() != null
                        && info.getGroupName().toLowerCase().contains(keyword.toLowerCase()))
                .sorted((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
