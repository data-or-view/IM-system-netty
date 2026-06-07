package com.im.core.user;

import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.core.cache.Cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户资料缓存装饰器。
 *
 * <p>只缓存用户公开资料这类读多写少的数据；在线状态实时性高，仍直接委托底层实现。</p>
 */
public class CachedUserManager implements IUserManager {

    private final IUserManager delegate;
    private final Cache<String, UserInformation> profileCache;

    public CachedUserManager(IUserManager delegate, Cache<String, UserInformation> profileCache) {
        this.delegate = delegate;
        this.profileCache = profileCache;
    }

    @Override
    public void register(String userId, String nickname, String faceUrl, String ex) {
        delegate.register(userId, nickname, faceUrl, ex);
        profileCache.invalidate(userId);
    }

    @Override
    public UserInformation getUserInformation(String userId) {
        Optional<UserInformation> cached = profileCache.get(userId);
        if (cached.isPresent()) {
            return cached.get();
        }
        UserInformation loaded = delegate.getUserInformation(userId);
        if (loaded != null) {
            profileCache.put(userId, loaded);
        }
        return loaded;
    }

    @Override
    public List<UserInformation> getUsersInfo(List<String> userIds) {
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>(userIds);
        Map<String, Optional<UserInformation>> cached = profileCache.getAllPresent(uniqueIds);
        List<String> misses = new ArrayList<>();
        Map<String, UserInformation> byUserId = new LinkedHashMap<>();

        for (String userId : uniqueIds) {
            Optional<UserInformation> info = cached.getOrDefault(userId, Optional.empty());
            if (info.isPresent()) {
                byUserId.put(userId, info.get());
            } else {
                misses.add(userId);
            }
        }

        if (!misses.isEmpty()) {
            for (UserInformation info : delegate.getUsersInfo(misses)) {
                if (info == null || info.getUserId() == null) {
                    continue;
                }
                byUserId.put(info.getUserId(), info);
                profileCache.put(info.getUserId(), info);
            }
        }

        return userIds.stream()
                .map(byUserId::get)
                .filter(info -> info != null)
                .toList();
    }

    @Override
    public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
        return delegate.getOnlineStatus(userIds);
    }

    @Override
    public void updateUserInformation(String userId, String nickname, String faceUrl,
                                      String ex, int globalRecvMsgOpt) {
        delegate.updateUserInformation(userId, nickname, faceUrl, ex, globalRecvMsgOpt);
        profileCache.invalidate(userId);
    }

    @Override
    public List<UserInformation> searchUsers(String keyword, int limit) {
        return delegate.searchUsers(keyword, limit);
    }
}
