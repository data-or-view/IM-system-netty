package com.im.core.friend;

import com.im.api.*;
import com.im.api.cache.ICache;
import com.im.core.cache.SafeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 本地好友管理器（单机开发/测试用）。
 *
 * 基于 ConcurrentHashMap 的内存实现。
 * 生产环境请换 DB 实现。
 *
 * 可选的缓存层（SafeCache 包裹，任何异常降级到内存数据源）。
 */
public class LocalFriendManager implements IFriendManager {

    private static final Logger log = LoggerFactory.getLogger(LocalFriendManager.class);
    private static final long FRIEND_CACHE_TTL = 300; // 5分钟

    /** ownerUserId → friendUserId set */
    private final ConcurrentMap<String, CopyOnWriteArraySet<String>> friends = new ConcurrentHashMap<>();

    /** ownerUserId → blockedUserId set */
    private final ConcurrentMap<String, CopyOnWriteArraySet<String>> blacks = new ConcurrentHashMap<>();

    /** ownerUserId → friendUserId → remark */
    private final ConcurrentMap<String, ConcurrentMap<String, String>> remarks = new ConcurrentHashMap<>();

    /** ownerUserId → friendUserId → isPinned */
    private final ConcurrentMap<String, ConcurrentMap<String, Boolean>> pinnedFlags = new ConcurrentHashMap<>();

    /** fromUserId → toUserId → 申请记录（最新的一个） */
    private final ConcurrentMap<String, ConcurrentMap<String, FriendApply>> applies = new ConcurrentHashMap<>();

    /** 好友列表缓存（SafeCache 包裹） */
    private final ICache<String, List<FriendInformation>> friendCache;

    public LocalFriendManager() {
        this(null);
    }

    public LocalFriendManager(ICache<String, List<FriendInformation>> friendCache) {
        this.friendCache = friendCache != null
                ? new SafeCache<>(friendCache, "LocalFriendManager")
                : null;
    }

    @Override
    public void applyAddFriend(String fromUserId, String toUserId, String reqMsg) {
        FriendApply apply = new FriendApply();
        apply.setFromUserId(fromUserId);
        apply.setToUserId(toUserId);
        apply.setReqMsg(reqMsg);
        apply.setCreateTime(System.currentTimeMillis());
        applies.computeIfAbsent(fromUserId, k -> new ConcurrentHashMap<>()).put(toUserId, apply);
        log.info("Friend apply: {} -> {}", fromUserId, toUserId);
    }

    @Override
    public void respondFriendApply(String userId, String fromUserId, String handleMsg, boolean agreed) {
        ConcurrentMap<String, FriendApply> fromApplies = applies.get(fromUserId);
        if (fromApplies == null) {
            log.warn("respondFriendApply: no applies from {}", fromUserId);
            return;
        }
        FriendApply apply = fromApplies.get(userId);
        if (apply == null) {
            log.warn("respondFriendApply: no apply from {} to {} (applies keys: {})", fromUserId, userId, fromApplies.keySet());
            return;
        }
        apply.setHandleResult(agreed ? 1 : 2);
        apply.setHandlerUserId(userId);
        apply.setHandleMsg(handleMsg);
        apply.setHandleTime(System.currentTimeMillis());
        if (agreed) {
            addFriend(userId, fromUserId);
            addFriend(fromUserId, userId);
            invalidateFriendCache(userId, fromUserId);
            log.info("Friend added: {} <-> {}", userId, fromUserId);
        }
    }

    @Override
    public List<FriendApply> getFriendApplyList(String userId, boolean onlyPending) {
        List<FriendApply> result = new ArrayList<>();
        for (ConcurrentMap<String, FriendApply> fromApplies : applies.values()) {
            FriendApply apply = fromApplies.get(userId);
            if (apply != null) {
                if (onlyPending && apply.getHandleResult() != 0) continue;
                result.add(apply);
            }
        }
        return result;
    }

    @Override
    public void deleteFriend(String ownerUserId, String friendUserId) {
        CopyOnWriteArraySet<String> set = friends.get(ownerUserId);
        if (set != null) set.remove(friendUserId);
        ConcurrentMap<String, String> remarkMap = remarks.get(ownerUserId);
        if (remarkMap != null) remarkMap.remove(friendUserId);
        ConcurrentMap<String, Boolean> pinnedMap = pinnedFlags.get(ownerUserId);
        if (pinnedMap != null) pinnedMap.remove(friendUserId);
        invalidateFriendCache(ownerUserId, friendUserId);
        log.info("Friend deleted: owner={}, friend={}", ownerUserId, friendUserId);
    }

    @Override
    public List<FriendInformation> getFriendList(String userId) {
        if (friendCache != null) {
            return friendCache.getOrLoad(
                    friendListKey(userId),
                    () -> buildFriendList(userId),
                    FRIEND_CACHE_TTL);
        }
        return buildFriendList(userId);
    }

    private List<FriendInformation> buildFriendList(String userId) {
        CopyOnWriteArraySet<String> friendIds = friends.get(userId);
        if (friendIds == null) return List.of();
        return friendIds.stream().map(fid -> {
            FriendInformation fi = new FriendInformation();
            fi.setOwnerUserId(userId);
            fi.setFriendUserId(fid);
            ConcurrentMap<String, String> remarkMap = remarks.get(userId);
            if (remarkMap != null) fi.setRemark(remarkMap.get(fid));
            ConcurrentMap<String, Boolean> pinnedMap = pinnedFlags.get(userId);
            if (pinnedMap != null && pinnedMap.get(fid) != null) {
                fi.setPinned(pinnedMap.get(fid));
            }
            return fi;
        }).collect(Collectors.toList());
    }

    @Override
    public boolean isFriend(String userIdA, String userIdB) {
        CopyOnWriteArraySet<String> set = friends.get(userIdA);
        return set != null && set.contains(userIdB);
    }

    @Override
    public void setFriendRemark(String ownerUserId, String friendUserId, String remark) {
        remarks.computeIfAbsent(ownerUserId, k -> new ConcurrentHashMap<>()).put(friendUserId, remark);
    }

    @Override
    public void setFriendPinned(String ownerUserId, String friendUserId, boolean pinned) {
        pinnedFlags.computeIfAbsent(ownerUserId, k -> new ConcurrentHashMap<>()).put(friendUserId, pinned);
        log.info("Friend pin: owner={}, friend={}, pinned={}", ownerUserId, friendUserId, pinned);
    }

    @Override
    public void addBlack(String ownerUserId, String blockedUserId) {
        blacks.computeIfAbsent(ownerUserId, k -> new CopyOnWriteArraySet<>()).add(blockedUserId);
    }

    @Override
    public void removeBlack(String ownerUserId, String blockedUserId) {
        CopyOnWriteArraySet<String> set = blacks.get(ownerUserId);
        if (set != null) set.remove(blockedUserId);
    }

    @Override
    public List<String> getBlackList(String userId) {
        Set<String> set = blacks.get(userId);
        return set != null ? new ArrayList<>(set) : List.of();
    }

    @Override
    public boolean isBlocked(String fromUserId, String toUserId) {
        CopyOnWriteArraySet<String> set = blacks.get(toUserId);
        return set != null && set.contains(fromUserId);
    }

    // ── 缓存失效 ──

    private void invalidateFriendCache(String userId, String friendUserId) {
        if (friendCache != null) {
            friendCache.delete(friendListKey(userId));
            friendCache.delete(friendListKey(friendUserId));
        }
    }

    private static String friendListKey(String userId) {
        return "friends:" + userId;
    }

    private void addFriend(String owner, String friend) {
        friends.computeIfAbsent(owner, k -> new CopyOnWriteArraySet<>()).add(friend);
    }
}
