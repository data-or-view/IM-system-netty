package com.im.bootstrap;

import com.im.api.ICallManager;
import com.im.api.IAuthenticator;
import com.im.api.IClusterMessageBus;
import com.im.api.IConversationManager;
import com.im.api.IFileStorageService;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IGroupMessageStore;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.INodeDiscovery;
import com.im.api.IRouteTable;
import com.im.api.ISequenceManager;
import com.im.api.ISingleMessageStore;
import com.im.api.IUserManager;
import com.im.api.MultiLoginStrategy;
import com.im.api.NodeInformation;
import com.im.common.retry.RetryExecutor;
import com.im.common.util.IMExecutors;
import com.im.config.Config;
import com.im.core.auth.IPasswordHasher;
import com.im.core.auth.IUserCredentialStore;
import com.im.core.auth.JwtAuthenticator;
import com.im.core.auth.Pbkdf2PasswordHasher;
import com.im.core.call.CallStateManager;
import com.im.core.call.GroupCallManager;
import com.im.core.call.LiveKitCallManager;
import com.im.core.call.RedisGroupCallStateStore;
import com.im.core.conversation.DbConversationManager;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.delivery.ClusterDeliveryHandler;
import com.im.core.delivery.ClusterSessionCommandHandler;
import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.delivery.RedisClusterMessageBus;
import com.im.core.discovery.RedisNodeDiscovery;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.file.DbFileObjectMetadataStore;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.file.RedisUploadSessionStore;
import com.im.core.friend.ClusterAwareFriendApplyNotifier;
import com.im.core.friend.DbFriendManager;
import com.im.core.friend.FriendApplyNotifier;
import com.im.core.group.ClusterAwareGroupApplyNotifier;
import com.im.core.group.DbGroupManager;
import com.im.core.group.GroupApplyNotifier;
import com.im.core.handler.ConnectionEventHandler;
import com.im.core.mq.RedisMessageQueue;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.retry.FailsafeRetryExecutor;
import com.im.core.seq.RedisSequenceManager;
import com.im.core.session.RedisSessionManager;
import com.im.core.session.SessionManager;
import com.im.core.store.DbMessageStore;
import com.im.core.store.GroupMessageStoreAdapter;
import com.im.core.store.SingleMessageStoreAdapter;
import com.im.core.user.DbUserManager;
import com.im.infrastructure.storage.file.MinioFileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Production composition root.
 *
 * <p>Only four bootstrap files should matter to readers: {@link IMServer} for
 * lifecycle, this factory for component construction, {@link TransportServer} for
 * Netty, and {@link DispatcherFactory} for API registration. The small records in
 * this file are intentionally private so bootstrap details do not leak as new
 * top-level concepts.</p>
 */
final class ServerComponentsFactory {

    private static final Logger log = LoggerFactory.getLogger(ServerComponentsFactory.class);
    private static boolean databaseFailed = false;

    private ServerComponentsFactory() {
    }

    static void resetDatabaseFailed() {
        databaseFailed = false;
    }

    static ServerComponents create(Config config) {
        RedisConfiguration redisConfig = requireRedisConfig(config);
        requireDatabaseEnabled(config);
        initDatabase(config);

        String nodeId = config.getString("im.node.id", "node-1");
        // Keep construction order explicit here: these objects are tightly coupled by
        // lifecycle and cluster guarantees, so hiding them behind more top-level modules
        // makes a single production dependency change harder to audit.
        RuntimeDependencies runtime = createRuntime(config, redisConfig, nodeId);
        ClusterDependencies cluster = createCluster(redisConfig, runtime.sessionManager(), nodeId);
        runtime.friendApplyNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        runtime.groupApplyNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        BusinessDependencies business = createBusiness(config, cluster.routeTable());
        StorageDependencies storage = createStorage(config, redisConfig, nodeId, business.retryExecutor());
        CallDependencies call = createCall(config, storage.messageQueue(), business.groupManager(), redisConfig);
        ConsumerDependencies consumers = createConsumers(nodeId, runtime, cluster, storage, business);
        ConnectionEventHandler connectionEventHandler = new ConnectionEventHandler(
                runtime.sessionManager(), runtime.pendingAcknowledgementManager(), cluster.routeTable(), nodeId);
        RequestAdmission requestAdmission = new DefaultRequestAdmission();
        ApiDispatcher dispatcher = DispatcherFactory.create(config, new DispatcherDependencies(
                nodeId, runtime, cluster, business, storage, call));
        TransportServer transportServer = new TransportServer(
                config, runtime.sessionManager(), connectionEventHandler, dispatcher,
                runtime.virtualExecutor(), requestAdmission);

        return new ServerComponents(new ServerRuntime(
                cluster.nodeDiscovery(),
                buildNodeInformation(config, nodeId),
                requestAdmission,
                config.getDuration("im.server.request-drain-timeout")
                        .orElse(java.time.Duration.ofSeconds(30)),
                cluster.clusterMessageBus(),
                storage.messageQueue(),
                consumers.persistenceConsumer(),
                consumers.deliveryConsumer(),
                transportServer,
                call.callStateManager(),
                connectionEventHandler::shutdown,
                runtime.pendingAcknowledgementManager()::shutdown,
                runtime.sessionManager()::clear,
                redisConfig,
                runtime.virtualExecutor()));
    }

    private static RuntimeDependencies createRuntime(Config config, RedisConfiguration redisConfig, String nodeId) {
        SessionManager sessionManager = new RedisSessionManager(redisConfig);
        applyMultiLoginStrategy(config, sessionManager);
        return new RuntimeDependencies(
                sessionManager,
                new PendingAcknowledgementManager(),
                IMExecutors.newVirtualThreadExecutor("im-dispatch"),
                new RuntimeFriendApplyNotifier(nodeId, sessionManager),
                new RuntimeGroupApplyNotifier(nodeId, sessionManager));
    }

    private static ClusterDependencies createCluster(RedisConfiguration redisConfig,
                                                     SessionManager sessionManager,
                                                     String nodeId) {
        return new ClusterDependencies(
                new RedisRouteTable(redisConfig, sessionManager, nodeId),
                new RedisClusterMessageBus(redisConfig, nodeId),
                new RedisNodeDiscovery(redisConfig));
    }

    private static BusinessDependencies createBusiness(Config config, IRouteTable routeTable) {
        JwtAuthenticator authenticator = new JwtAuthenticator(
                config.getString("im.token.secret", "im-system-dev-secret-change-in-production"));
        RetryExecutor retryExecutor = new FailsafeRetryExecutor();
        IGroupManager groupManager = new DbGroupManager(retryExecutor);
        IConversationManager conversationManager = new DbConversationManager(retryExecutor);
        IFriendManager friendManager = new DbFriendManager(retryExecutor);
        DbUserManager userManager = new DbUserManager(retryExecutor, routeTable);
        IPasswordHasher passwordHasher = new Pbkdf2PasswordHasher();
        return new BusinessDependencies(
                authenticator, retryExecutor, groupManager, conversationManager, friendManager,
                userManager, userManager, passwordHasher);
    }

    private static StorageDependencies createStorage(Config config,
                                                     RedisConfiguration redisConfig,
                                                     String nodeId,
                                                     RetryExecutor retryExecutor) {
        ISequenceManager sequenceManager = new RedisSequenceManager(redisConfig);
        IMessageStore messageStore = new DbMessageStore(retryExecutor);
        ISingleMessageStore singleMessageStore = new SingleMessageStoreAdapter(messageStore);
        IGroupMessageStore groupMessageStore = new GroupMessageStoreAdapter(messageStore);
        IMessageQueue messageQueue = new RedisMessageQueue(redisConfig, nodeId);
        IFileStorageService fileStorage = new MinioFileStorageService(
                config.getString("im.minio.endpoint").orElse("http://127.0.0.1:9000"),
                config.getString("im.minio.access-key").orElse("minioadmin"),
                config.getString("im.minio.secret-key").orElse("minioadmin"));
        String fileBucket = config.getString("im.minio.bucket").orElse("im-system");
        DirectFileTransferUseCase directFileTransferUseCase = new DirectFileTransferUseCase(
                fileStorage,
                new RedisUploadSessionStore(redisConfig),
                new DbFileObjectMetadataStore(fileBucket),
                fileBucket,
                config.getInt("im.minio.presign-expire-seconds", 900));
        return new StorageDependencies(
                sequenceManager, messageStore, singleMessageStore, groupMessageStore, messageQueue,
                fileStorage, directFileTransferUseCase);
    }

    private static CallDependencies createCall(Config config, IMessageQueue messageQueue,
                                               IGroupManager groupManager,
                                               RedisConfiguration redisConfig) {
        ICallManager callManager = null;
        if (config.getBoolean("im.call.enabled", false)) {
            callManager = new LiveKitCallManager(
                    config.getString("im.call.api-key", "devkey"),
                    config.getString("im.call.api-secret", ""),
                    config.getString("im.call.sfu-endpoint", "ws://localhost:7880"));
            log.info("LiveKitCallManager enabled: endpoint={}", config.getString("im.call.sfu-endpoint"));
        }

        CallStateManager callStateManager = callManager != null
                ? new CallStateManager(messageQueue, config.getLong("im.call.timeout-seconds", 30))
                : null;
        GroupCallManager groupCallManager = callManager != null
                ? new GroupCallManager(groupManager, callManager, new RedisGroupCallStateStore(redisConfig),
                config.getInt("im.call.group.max-participants", 16))
                : null;
        return new CallDependencies(callManager, callStateManager, groupCallManager);
    }

    private static ConsumerDependencies createConsumers(String nodeId,
                                                        RuntimeDependencies runtime,
                                                        ClusterDependencies cluster,
                                                        StorageDependencies storage,
                                                        BusinessDependencies business) {
        PersistenceConsumer persistenceConsumer = new PersistenceConsumer(
                storage.messageQueue(), storage.singleMessageStore(), storage.groupMessageStore(),
                business.conversationManager(), business.groupManager());
        DeliveryConsumer deliveryConsumer = new DeliveryConsumer(
                storage.messageQueue(), runtime.sessionManager(), cluster.routeTable(),
                cluster.clusterMessageBus(), nodeId, business.groupManager());

        ClusterDeliveryHandler clusterDeliveryHandler = new ClusterDeliveryHandler(runtime.sessionManager());
        cluster.clusterMessageBus().subscribe("SINGLE_CHAT", clusterDeliveryHandler);
        cluster.clusterMessageBus().subscribe("GROUP_CHAT", clusterDeliveryHandler);
        cluster.clusterMessageBus().subscribe("CLUSTER_COMMAND", new ClusterSessionCommandHandler(runtime.sessionManager()));
        cluster.clusterMessageBus().subscribe("CLUSTER_COMMAND", runtime.friendApplyNotifier()::handleClusterPush);
        cluster.clusterMessageBus().subscribe("CLUSTER_COMMAND", runtime.groupApplyNotifier()::handleClusterPush);
        return new ConsumerDependencies(persistenceConsumer, deliveryConsumer);
    }

    private static void applyMultiLoginStrategy(Config config, SessionManager sessionManager) {
        String strategyName = config.getString("im.login.multi-strategy", "ALLOW_MULTIPLE");
        try {
            MultiLoginStrategy strategy = MultiLoginStrategy.valueOf(strategyName);
            sessionManager.setLoginStrategy(strategy);
            log.info("Multi-login strategy set: {}", strategy);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid multi-login strategy '{}', using ALLOW_MULTIPLE", strategyName);
        }
    }

    private static RedisConfiguration requireRedisConfig(Config config) {
        RedisConfiguration redisConfig = buildRedisConfig(config);
        if (redisConfig == null) {
            throw new IllegalStateException(
                    "Cluster mode requires Redis. Set im.redis.host (and im.db.enabled=true).");
        }
        return redisConfig;
    }

    private static void requireDatabaseEnabled(Config config) {
        if (!dbEnabled(config)) {
            throw new IllegalStateException(
                    "Cluster mode requires database. Set im.db.enabled=true and initialize schema.");
        }
    }

    private static RedisConfiguration buildRedisConfig(Config config) {
        String redisHost = config.getString("im.redis.host").orElse(null);
        if (redisHost == null || redisHost.isEmpty()) return null;

        int redisPort = config.getInt("im.redis.port", 6379);
        String redisUsername = config.getString("im.redis.username").orElse("");
        String redisPassword = config.getString("im.redis.password").orElse("");
        int redisDatabase = config.getInt("im.redis.database", 0);
        String redisClusterNodes = config.getString("im.redis.cluster.nodes").orElse(null);

        RedisConfiguration.Builder rcb = RedisConfiguration.builder()
                .username(redisUsername)
                .password(redisPassword)
                .database(redisDatabase);
        if (redisClusterNodes != null && !redisClusterNodes.isEmpty()) {
            rcb.clusterNodes(redisClusterNodes.split(","));
        } else {
            rcb.host(redisHost).port(redisPort);
        }
        return rcb.build();
    }

    private static void initDatabase(Config config) {
        String jdbcUrl = config.getString("im.db.jdbc-url").orElse(null);
        DatabaseConfiguration dbConfig = jdbcUrl != null
                ? new DatabaseConfiguration.Builder()
                .jdbcUrl(jdbcUrl)
                .username(config.getString("im.db.username", "root"))
                .password(config.getString("im.db.password", "password"))
                .build()
                : DatabaseConfiguration.develop();
        try {
            MyBatisPlusFactory.init(dbConfig);
            SchemaInitializer.initialize(MyBatisPlusFactory.getDataSource(),
                    config.getString("im.db.schema").orElse("auto"));
            log.info("Database initialized: jdbcUrl={}", dbConfig.getJdbcUrl());
        } catch (Exception e) {
            log.error("Database initialization failed", e);
            databaseFailed = true;
            throw new IllegalStateException("Database initialization failed", e);
        }
    }

    private static boolean dbEnabled(Config config) {
        if (databaseFailed) return false;
        return config.getBoolean("im.db.enabled").orElse(false);
    }

    private static NodeInformation buildNodeInformation(Config config, String nodeId) {
        String host = "127.0.0.1";
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            // Keep startup tolerant in local/dev networks where host discovery can fail;
            // Redis node discovery still needs a stable fallback address.
        }
        int servicePort = config.getBoolean("im.ws.enabled", true) ? config.getInt("im.ws.port", 8081) : 0;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("webSocketPort", String.valueOf(servicePort));
        return new NodeInformation(nodeId, host, servicePort, attrs);
    }

    private static final class RuntimeFriendApplyNotifier implements FriendApplyNotifier {
        private final String nodeId;
        private final SessionManager sessionManager;
        private volatile ClusterAwareFriendApplyNotifier delegate;

        private RuntimeFriendApplyNotifier(String nodeId, SessionManager sessionManager) {
            this.nodeId = nodeId;
            this.sessionManager = sessionManager;
        }

        void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
            this.delegate = new ClusterAwareFriendApplyNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
        }

        @Override
        public void notifyApplyCreated(String toUserId, com.im.api.FriendApply apply) {
            if (delegate != null) delegate.notifyApplyCreated(toUserId, apply);
        }

        @Override
        public void notifyApplyHandled(String fromUserId, com.im.api.FriendApply apply) {
            if (delegate != null) delegate.notifyApplyHandled(fromUserId, apply);
        }

        void handleClusterPush(com.im.api.ClusterMessage message) {
            if (delegate != null) delegate.handleClusterPush(message);
        }
    }

    private static final class RuntimeGroupApplyNotifier implements GroupApplyNotifier {
        private final String nodeId;
        private final SessionManager sessionManager;
        private volatile ClusterAwareGroupApplyNotifier delegate;

        private RuntimeGroupApplyNotifier(String nodeId, SessionManager sessionManager) {
            this.nodeId = nodeId;
            this.sessionManager = sessionManager;
        }

        void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
            this.delegate = new ClusterAwareGroupApplyNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
        }

        @Override
        public void notifyApplyCreated(java.util.List<String> managerUserIds, com.im.api.GroupApply apply) {
            if (delegate != null) delegate.notifyApplyCreated(managerUserIds, apply);
        }

        @Override
        public void notifyApplyHandled(String applicantUserId, com.im.api.GroupApply apply) {
            if (delegate != null) delegate.notifyApplyHandled(applicantUserId, apply);
        }

        void handleClusterPush(com.im.api.ClusterMessage message) {
            if (delegate != null) delegate.handleClusterPush(message);
        }
    }

    record RuntimeDependencies(SessionManager sessionManager,
                               PendingAcknowledgementManager pendingAcknowledgementManager,
                               ExecutorService virtualExecutor,
                               RuntimeFriendApplyNotifier friendApplyNotifier,
                               RuntimeGroupApplyNotifier groupApplyNotifier) {
    }

    record ClusterDependencies(IRouteTable routeTable,
                               IClusterMessageBus clusterMessageBus,
                               INodeDiscovery nodeDiscovery) {
    }

    record BusinessDependencies(IAuthenticator authenticator,
                                RetryExecutor retryExecutor,
                                IGroupManager groupManager,
                                IConversationManager conversationManager,
                                IFriendManager friendManager,
                                IUserManager userManager,
                                IUserCredentialStore credentialStore,
                                IPasswordHasher passwordHasher) {
    }

    record StorageDependencies(ISequenceManager sequenceManager,
                               IMessageStore messageStore,
                               ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore,
                               IMessageQueue messageQueue,
                               IFileStorageService fileStorage,
                               DirectFileTransferUseCase directFileTransferUseCase) {
    }

    record CallDependencies(ICallManager callManager,
                            CallStateManager callStateManager,
                            GroupCallManager groupCallManager) {
    }

    private record ConsumerDependencies(PersistenceConsumer persistenceConsumer,
                                        DeliveryConsumer deliveryConsumer) {
    }
}
