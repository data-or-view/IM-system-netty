package com.im.core.user;

import com.im.api.IRouteTable;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.core.cache.Cache;
import com.im.core.cache.SafeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地用户管理器（单机开发/测试用）。
 *
 * 基于 ConcurrentHashMap 的内存实现，节点重启后数据丢失。
 * 生产环境请换 DB 实现。
 *
 * 可选的缓存层（SafeCache 包裹，任何异常降级到内存数据源）。
 */
public class LocalUserManager implements IUserManager {

    private static final Logger log = LoggerFactory.getLogger(LocalUserManager.class);

    private static final long USER_CACHE_TTL = 300; // 5分钟

    private final ConcurrentMap<String, UserInformation> users = new ConcurrentHashMap<>();
    private final Cache<String, UserInformation> userCache;
    private final IRouteTable routeTable;

    public LocalUserManager() {
        this(null, null);
    }

    public LocalUserManager(IRouteTable routeTable) {
        this(routeTable, null);
    }

    public LocalUserManager(IRouteTable routeTable, Cache<String, UserInformation> userCache) {
        this.routeTable = routeTable;
        this.userCache = userCache != null
                ? new SafeCache<>(userCache, "LocalUserManager")
                : null;
    }

    @Override
    public void register(String userId, String nickname, String faceUrl, String ex) {
        if (users.containsKey(userId)) {
            throw new ImException(ImErrorCode.CONFLICT, "User already exists: " + userId);
        }
        UserInformation info = new UserInformation(userId, nickname != null ? nickname : userId);
        info.setFaceUrl(faceUrl);
        info.setEx(ex);
        users.put(userId, info);
        log.info("User registered: userId={}, nickname={}", userId, info.getNickname());
    }

    @Override
    public UserInformation getUserInformation(String userId) {
        // 缓存优先
        if (userCache != null) {
            return userCache.get(userId).orElseGet(() -> {
                UserInformation info = users.get(userId);
                if (info == null) {
                    throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
                }
                userCache.put(userId, info);
                return info;
            });
        }
        UserInformation info = users.get(userId);
        if (info == null) {
            throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
        }
        return info;
    }

    @Override
    public List<UserInformation> getUsersInfo(List<String> userIds) {
        return userIds.stream()
                .map(users::get)
                .filter(u -> u != null)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
        Map<String, List<Integer>> result = new HashMap<>();
        for (String uid : userIds) {
            result.put(uid, Collections.emptyList());
        }
        if (routeTable != null) {
            result.putAll(routeTable.batchGetOnlinePlatforms(userIds));
        }
        return result;
    }

    @Override
    public void updateUserInformation(String userId, String nickname, String faceUrl,
                                      String ex, int globalRecvMsgOpt) {
        UserInformation info = users.get(userId);
        if (info == null) {
            throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
        }
        if (nickname != null) info.setNickname(nickname);
        if (faceUrl != null) info.setFaceUrl(faceUrl);
        if (ex != null) info.setEx(ex);
        if (globalRecvMsgOpt >= 0) info.setGlobalRecvMsgOpt(globalRecvMsgOpt);
        if (userCache != null) userCache.invalidate(userId);
    }

    @Override
    public List<UserInformation> searchUsers(String keyword, int limit) {
        String lower = keyword.toLowerCase();
        return users.values().stream()
                .filter(u -> u.getUserId().toLowerCase().contains(lower)
                        || u.getNickname().toLowerCase().contains(lower))
                .limit(limit)
                .toList();
    }
}
