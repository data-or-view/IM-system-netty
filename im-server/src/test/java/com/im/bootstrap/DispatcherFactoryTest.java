package com.im.bootstrap;

import com.im.api.IAuthenticator;
import com.im.api.TokenRefreshResult;
import com.im.api.ICallManager;
import com.im.api.IClusterMessageBus;
import com.im.api.IConversationManager;
import com.im.api.IFileStorageService;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IGroupMessageStore;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.IRouteTable;
import com.im.api.ISequenceManager;
import com.im.api.ISingleMessageStore;
import com.im.api.IUserManager;
import com.im.api.Operation;
import com.im.common.retry.RetryExecutor;
import com.im.config.Config;
import com.im.core.auth.IPasswordHasher;
import com.im.core.auth.IUserCredentialStore;
import com.im.core.call.CallStateManager;
import com.im.core.call.GroupCallManager;
import com.im.core.call.GroupCallSession;
import com.im.core.call.GroupCallStateStore;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.file.FileObjectMetadata;
import com.im.core.file.FileObjectMetadataStore;
import com.im.core.file.UploadSession;
import com.im.core.file.UploadSessionStore;
import com.im.core.redis.RedisConfiguration;
import com.im.core.session.SessionManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatcherFactoryTest {

    @Test
    void registersEveryDeclaredOperation() {
        ApiDispatcher dispatcher = DispatcherFactory.create(new EmptyConfig(), dependencies());

        List<String> registered = dispatcher.registeredOperations();

        // Operation is the protocol's single source of truth; the bootstrap layer must not
        // silently forget a new operation when HTTP/WS adapters already know how to parse it.
        Arrays.stream(Operation.values())
                .map(Operation::opName)
                .forEach(op -> assertTrue(registered.contains(op), "missing handler for operation: " + op));
    }

    private static DispatcherDependencies dependencies() {
        SessionManager sessionManager = new SessionManager();
        IAuthenticator authenticator = new TestAuthenticator();
        IUserManager userManager = fake(IUserManager.class);

        return new DispatcherDependencies(
                "node-test",
                new ServerComponentsFactory.RuntimeDependencies(
                        sessionManager,
                        new PendingAcknowledgementManager(),
                        fake(ExecutorService.class),
                        null,
                        null),
                new ServerComponentsFactory.ClusterDependencies(
                        fake(IRouteTable.class),
                        fake(IClusterMessageBus.class),
                        fake(com.im.api.INodeDiscovery.class)),
                new ServerComponentsFactory.BusinessDependencies(
                        authenticator,
                        fake(RetryExecutor.class),
                        fake(IGroupManager.class),
                        fake(IConversationManager.class),
                        fake(IFriendManager.class),
                        userManager,
                        fake(IUserCredentialStore.class),
                        fake(IPasswordHasher.class)),
                new ServerComponentsFactory.StorageDependencies(
                        fake(ISequenceManager.class),
                        fake(IMessageStore.class),
                        fake(ISingleMessageStore.class),
                        fake(IGroupMessageStore.class),
                        fake(IMessageQueue.class),
                        fake(IFileStorageService.class),
                        directFileTransferUseCase()),
                new ServerComponentsFactory.CallDependencies(
                        fake(ICallManager.class),
                        (CallStateManager) null,
                        new GroupCallManager(fake(IGroupManager.class), fake(ICallManager.class),
                                new NoopGroupCallStateStore(), 16)));
    }

    private static final class NoopGroupCallStateStore implements GroupCallStateStore {
        @Override
        public GroupCallSession getActiveByGroup(String groupId) {
            return null;
        }

        @Override
        public GroupCallSession createIfAbsent(GroupCallSession session) {
            return session;
        }

        @Override
        public GroupCallSession addParticipant(String groupId, String userId) {
            return null;
        }

        @Override
        public GroupCallSession removeParticipant(String groupId, String userId) {
            return null;
        }

        @Override
        public GroupCallSession end(String groupId) {
            return null;
        }
    }

    private static DirectFileTransferUseCase directFileTransferUseCase() {
        return new DirectFileTransferUseCase(
                fake(IFileStorageService.class),
                new InMemoryUploadSessionStore(),
                new InMemoryFileObjectMetadataStore(),
                "im-system",
                900);
    }

    private static final class InMemoryUploadSessionStore implements UploadSessionStore {
        private final Map<String, UploadSession> byFileId = new HashMap<>();
        private final Map<String, UploadSession> byUploadId = new HashMap<>();

        @Override
        public void save(UploadSession session) {
            byFileId.put(session.fileId(), session);
            if (session.uploadId() != null) byUploadId.put(session.uploadId(), session);
        }

        @Override
        public UploadSession getByFileId(String fileId) {
            return byFileId.get(fileId);
        }

        @Override
        public UploadSession getByUploadId(String uploadId) {
            return byUploadId.get(uploadId);
        }

        @Override
        public void delete(UploadSession session) {
            byFileId.remove(session.fileId());
            if (session.uploadId() != null) byUploadId.remove(session.uploadId());
        }
    }

    private static final class InMemoryFileObjectMetadataStore implements FileObjectMetadataStore {
        private final Map<String, FileObjectMetadata> objects = new HashMap<>();

        @Override
        public void save(FileObjectMetadata metadata) {
            objects.put(metadata.fileId(), metadata);
        }

        @Override
        public FileObjectMetadata findByFileId(String fileId) {
            return objects.get(fileId);
        }
    }

    private static final class TestAuthenticator implements IAuthenticator {
        @Override
        public String issueToken(String userId, Duration ttl) {
            return "token-" + userId;
        }

        @Override
        public String authenticate(String token) {
            return "user-from-token";
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

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0F;
        if (returnType == Double.TYPE) return 0D;
        if (returnType == Character.TYPE) return '\0';
        return null;
    }

    private static final class EmptyConfig implements Config {
        @Override
        public Optional<String> getString(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getLong(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> getDuration(String key) {
            return Optional.empty();
        }

        @Override
        public boolean hasKey(String key) {
            return false;
        }
    }
}
