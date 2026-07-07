package com.im.core.user;

import com.im.api.IUserManager;
import com.im.api.UserInformation;
import com.im.core.cache.SafeCache;
import com.im.core.cache.TestInMemoryCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachedUserManagerTest {

    @Test
    void getUserInformationUsesCacheUntilUserIsUpdated() {
        RecordingUserManager delegate = new RecordingUserManager();
        delegate.user = new UserInformation("u1", "Alice");
        CachedUserManager manager = new CachedUserManager(delegate,
                new SafeCache<>(new TestInMemoryCache<>(), "user-profile-test"));

        assertEquals("Alice", manager.getUserInformation("u1").getNickname());
        delegate.user = new UserInformation("u1", "Alice stale source");
        assertEquals("Alice", manager.getUserInformation("u1").getNickname());
        assertEquals(1, delegate.infoCalls);

        delegate.user = new UserInformation("u1", "Alice fresh");
        manager.updateUserInformation("u1", "Alice fresh", null, null, -1);

        assertEquals("Alice fresh", manager.getUserInformation("u1").getNickname());
        assertEquals(2, delegate.infoCalls);
    }

    @Test
    void getUsersInfoLoadsOnlyCacheMissesAndBackfillsCache() {
        RecordingUserManager delegate = new RecordingUserManager();
        delegate.users = Map.of(
                "u1", new UserInformation("u1", "Alice"),
                "u2", new UserInformation("u2", "Bob")
        );
        CachedUserManager manager = new CachedUserManager(delegate,
                new SafeCache<>(new TestInMemoryCache<>(), "user-profile-test"));

        List<UserInformation> first = manager.getUsersInfo(List.of("u1", "u2"));
        List<UserInformation> second = manager.getUsersInfo(List.of("u2", "u1"));

        assertEquals(List.of("u1", "u2"), first.stream().map(UserInformation::getUserId).toList());
        assertEquals(List.of("u2", "u1"), second.stream().map(UserInformation::getUserId).toList());
        assertEquals(1, delegate.usersInfoCalls);
    }

    private static final class RecordingUserManager implements IUserManager {
        private UserInformation user;
        private Map<String, UserInformation> users = Map.of();
        private int infoCalls;
        private int usersInfoCalls;

        @Override
        public void register(String userId, String nickname, String faceUrl, String ex) {
        }

        @Override
        public UserInformation getUserInformation(String userId) {
            infoCalls++;
            return user != null ? user : users.get(userId);
        }

        @Override
        public List<UserInformation> getUsersInfo(List<String> userIds) {
            usersInfoCalls++;
            return userIds.stream()
                    .map(users::get)
                    .filter(info -> info != null)
                    .toList();
        }

        @Override
        public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
            return Map.of();
        }

        @Override
        public void updateUserInformation(String userId, String nickname, String faceUrl,
                                          String ex, int globalRecvMsgOpt) {
            if (user != null) {
                user.setNickname(nickname != null ? nickname : user.getNickname());
            }
        }

        @Override
        public List<UserInformation> searchUsers(String keyword, int limit) {
            return List.of();
        }
    }
}
