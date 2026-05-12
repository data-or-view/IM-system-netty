package com.im.core.user;

import com.im.api.ImErrorCode;
import com.im.api.ImException;
import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.api.cache.ICache;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.cache.SafeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

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
    private final ConcurrentMap<String, List<Integer>> onlineStatus = new ConcurrentHashMap<>();
    private final ICache<String, UserInformation> userCache;

    public LocalUserManager() {
        this(null);
    }

    public LocalUserManager(ICache<String, UserInformation> userCache) {
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
            return userCache.getOrLoad(userId, () -> {
                UserInformation info = users.get(userId);
                if (info == null) {
                    throw new ImException(ImErrorCode.NOT_FOUND, "User not found: " + userId);
                }
                return info;
            }, USER_CACHE_TTL);
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
        Map<String, List<Integer>> result = new ConcurrentHashMap<>();
        for (String uid : userIds) {
            result.put(uid, onlineStatus.getOrDefault(uid, Collections.emptyList()));
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
        if (userCache != null) userCache.delete(userId);
    }

    /** 设置用户在线平台（供 LoginHandler/ConnectionEventHandler 调用） */
    public void setOnline(String userId, List<Integer> platforms) {
        onlineStatus.put(userId, platforms);
    }

    /** 用户离线 */
    public void setOffline(String userId) {
        onlineStatus.remove(userId);
    }
}
