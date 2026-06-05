package com.im.core.usecase;

import com.im.api.IAuthenticator;
import com.im.api.IMessageStore;
import com.im.api.IUserManager;
import com.im.api.Message;
import com.im.api.UserInformation;
import com.im.common.exception.UnauthorizedException;
import com.im.common.exception.ValidationException;
import com.im.core.auth.IPasswordHasher;
import com.im.core.auth.IUserCredentialStore;
import com.im.core.auth.Pbkdf2PasswordHasher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginUseCaseCredentialTest {

    @Test
    void registerStoresHashAndLoginRejectsWrongPassword() {
        CredentialStore credentials = new CredentialStore();
        PasswordHasher hasher = new PasswordHasher();
        RegisterUseCase register = new RegisterUseCase(new UserManager(), credentials, hasher);

        register.execute("alice", "Alice", "", "secret123");

        String storedHash = credentials.passwordHash("alice");
        assertFalse(storedHash.contains("secret123"));
        assertTrue(hasher.matches("secret123", storedHash));

        LoginUseCase login = new LoginUseCase(new StubAuthenticator(), new EmptyMessageStore(), credentials, hasher);

        assertThrows(UnauthorizedException.class, () -> login.execute("alice", "bad-password", 1, 0));
        assertEquals("token-alice", login.execute("alice", "secret123", 1, 0).token());
    }

    @Test
    void registerRequiresPasswordWhenCredentialStoreIsEnabled() {
        RegisterUseCase register = new RegisterUseCase(new UserManager(), new CredentialStore(), new PasswordHasher());

        assertThrows(ValidationException.class, () -> register.execute("alice", "Alice", "", ""));
    }

    @Test
    void existingUserWithoutHashCanInitializePasswordByRegisteringAgain() {
        UserManager users = new UserManager();
        users.register("alice", "Alice", "", null);
        CredentialStore credentials = new CredentialStore();
        PasswordHasher hasher = new PasswordHasher();
        RegisterUseCase register = new RegisterUseCase(users, credentials, hasher);

        RegisterUseCase.RegisterResult result = register.execute("alice", "Alice", "", "secret123");

        assertTrue(result.alreadyExists());
        assertTrue(hasher.matches("secret123", credentials.passwordHash("alice")));
    }

    @Test
    void pbkdf2HasherStoresSaltedHashWithoutPlaintext() {
        Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();

        String first = hasher.hash("secret123");
        String second = hasher.hash("secret123");

        assertFalse(first.contains("secret123"));
        assertFalse(second.contains("secret123"));
        assertFalse(first.equals(second));
        assertTrue(hasher.matches("secret123", first));
        assertFalse(hasher.matches("bad-password", first));
    }

    private static final class UserManager implements IUserManager {
        private final Map<String, UserInformation> users = new ConcurrentHashMap<>();

        @Override
        public void register(String userId, String nickname, String faceUrl, String ex) {
            UserInformation user = new UserInformation(userId, nickname);
            user.setFaceUrl(faceUrl);
            users.put(userId, user);
        }

        @Override
        public UserInformation getUserInformation(String userId) {
            return users.get(userId);
        }

        @Override
        public List<UserInformation> getUsersInfo(List<String> userIds) {
            return List.of();
        }

        @Override
        public Map<String, List<Integer>> getOnlineStatus(List<String> userIds) {
            return Map.of();
        }

        @Override
        public void updateUserInformation(String userId, String nickname, String faceUrl,
                                          String ex, int globalRecvMsgOpt) {
        }

        @Override
        public List<UserInformation> searchUsers(String keyword, int limit) {
            return List.of();
        }
    }

    private static final class CredentialStore implements IUserCredentialStore {
        private final Map<String, String> hashes = new ConcurrentHashMap<>();

        @Override
        public String getPasswordHash(String userId) {
            return hashes.get(userId);
        }

        @Override
        public void setPasswordHash(String userId, String passwordHash) {
            hashes.put(userId, passwordHash);
        }

        String passwordHash(String userId) {
            return hashes.get(userId);
        }
    }

    private static final class PasswordHasher implements IPasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hash:" + rawPassword.hashCode();
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return hash(rawPassword).equals(passwordHash);
        }
    }

    private static final class StubAuthenticator implements IAuthenticator {
        @Override
        public String issueToken(String userId, Duration ttl) {
            return "token-" + userId;
        }

        @Override
        public String authenticate(String token) {
            return "alice";
        }

        @Override
        public String issueRefreshToken(String userId, Duration ttl, int appManagerLevel) {
            return "refresh-" + userId;
        }

        @Override
        public TokenRefreshResult refreshAccessToken(String refreshToken) {
            return new TokenRefreshResult("token", null);
        }
    }

    private static final class EmptyMessageStore implements IMessageStore {
        @Override
        public void save(Message msg) {
        }

        @Override
        public List<Message> pullOffline(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
            return List.of();
        }

        @Override
        public void markDelivered(String userId, List<String> msgIds) {
        }
    }
}
