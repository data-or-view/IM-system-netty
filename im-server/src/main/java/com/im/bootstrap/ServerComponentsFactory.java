package com.im.bootstrap;

import com.im.api.IAuthenticator;
import com.im.api.ICallManager;
import com.im.api.IConversationManager;
import com.im.api.IFileStorageService;
import com.im.api.IFriendManager;
import com.im.api.IGroupManager;
import com.im.api.IGroupMessageStore;
import com.im.api.IMessageQueue;
import com.im.api.IMessageStore;
import com.im.api.IPasswordHasher;
import com.im.api.IRouteTable;
import com.im.api.ISequenceManager;
import com.im.api.ISingleMessageStore;
import com.im.api.ISystemMessageStore;
import com.im.api.IUserCredentialStore;
import com.im.api.IUserManager;
import com.im.api.MultiLoginStrategy;
import com.im.api.SendMessageIdempotency;
import com.im.common.retry.RetryExecutor;
import com.im.common.util.IMExecutors;
import com.im.config.Config;
import com.im.core.auth.DbRefreshTokenStore;
import com.im.core.auth.JwtAuthenticator;
import com.im.core.auth.Pbkdf2PasswordHasher;
import com.im.core.call.CallStateManager;
import com.im.core.call.GroupCallManager;
import com.im.core.call.LiveKitCallManager;
import com.im.core.call.RedisGroupCallStateStore;
import com.im.core.call.RedisSingleCallStateStore;
import com.im.core.cache.Cache;
import com.im.core.cache.SafeCache;
import com.im.core.cache.redis.RedisJsonCache;
import com.im.core.conversation.CachedConversationManager;
import com.im.core.conversation.DbConversationManager;
import com.im.core.conversation.ConversationListSnapshot;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.file.DirectFileTransferUseCase;
import com.im.core.friend.DbFriendManager;
import com.im.core.group.CachedGroupManager;
import com.im.core.group.DbGroupManager;
import com.im.core.group.GroupMemberIdsSnapshot;
import com.im.core.group.GroupMemberListSnapshot;
import com.im.core.handler.ConnectionEventHandler;
import com.im.core.ratelimit.RedisRateLimiter;
import com.im.core.redis.RedisConfiguration;
import com.im.core.retry.FailsafeRetryExecutor;
import com.im.core.serialization.Serializer;
import com.im.core.serialization.jackson.JacksonSerializer;
import com.im.core.session.RedisSessionManager;
import com.im.core.session.SessionManager;
import com.im.core.user.CachedUserManager;
import com.im.core.user.DbUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Function;

/**
 * Production composition root.
 *
 * <p>Heavy Redis, database,
 * storage, and consumer construction lives in package-private factory slices so
 * this file shows the runtime graph without hiding cluster-critical wiring.</p>
 */
final class ServerComponentsFactory {

    private static final Logger log = LoggerFactory.getLogger(ServerComponentsFactory.class);

    private ServerComponentsFactory() {
    }

    static void resetDatabaseFailed() {
        DatabaseComponentsFactory.resetDatabaseFailed();
    }

    static ServerComponents create(Config config) {
        RedisConfiguration redisConfig = RedisComponentsFactory.requireRedisConfig(config);
        DatabaseComponentsFactory.requireDatabaseEnabled(config);
        DatabaseComponentsFactory.initDatabase(config);

        String nodeId = config.getString("im.node.id", BootstrapDefaults.NODE_ID);
        // Keep construction order explicit here: these objects are tightly coupled by
        // lifecycle and cluster guarantees, so hiding them behind broader abstractions
        // makes a single production dependency change harder to audit.
        RuntimeDependencies runtime = createRuntime(config, redisConfig, nodeId);
        ClusterDependencies cluster = RedisComponentsFactory.createCluster(redisConfig, runtime.sessionManager(), nodeId);
        runtime.friendApplyNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        runtime.groupApplyNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        runtime.systemMessageNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        runtime.messageRevokeNotifier().bindCluster(cluster.routeTable(), cluster.clusterMessageBus());
        BusinessDependencies business = createBusiness(config, redisConfig, cluster.routeTable());
        StorageDependencies storage = StorageComponentsFactory.createStorage(config, redisConfig, nodeId, business.retryExecutor());
        CallDependencies call = createCall(config, storage.messageQueue(), business.groupManager(), redisConfig);
        ConsumerDependencies consumers = ConsumerComponentsFactory.createConsumers(config, nodeId, runtime, cluster, storage, business);
        ConnectionEventHandler connectionEventHandler = new ConnectionEventHandler(
                runtime.sessionManager(), runtime.pendingAcknowledgementManager(), cluster.routeTable(), nodeId);
        RequestAdmission requestAdmission = new DefaultRequestAdmission();
        ApiDispatcher dispatcher = DispatcherFactory.create(config, new DispatcherDependencies(
                nodeId, runtime, cluster, business, storage, call, new RedisRateLimiter(redisConfig)));
        TransportServer transportServer = new TransportServer(
                config, runtime.sessionManager(), connectionEventHandler, dispatcher,
                runtime.virtualExecutor(), requestAdmission);

        return new ServerComponents(new ServerRuntime(
                cluster.nodeDiscovery(),
                RedisComponentsFactory.buildNodeInformation(config, nodeId),
                requestAdmission,
                config.getDuration("im.server.request-drain-timeout")
                        .orElse(BootstrapDefaults.REQUEST_DRAIN_TIMEOUT),
                cluster.clusterMessageBus(),
                storage.messageQueue(),
                consumers.persistenceConsumer(),
                consumers.deliveryConsumer(),
                consumers.businessMessageDlqCompensator(),
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
                new RuntimeGroupApplyNotifier(nodeId, sessionManager),
                new RuntimeSystemMessageNotifier(nodeId, sessionManager),
                new RuntimeMessageRevokeNotifier(nodeId, sessionManager));
    }

    private static BusinessDependencies createBusiness(Config config,
                                                       RedisConfiguration redisConfig,
                                                       IRouteTable routeTable) {
        RetryExecutor retryExecutor = new FailsafeRetryExecutor();
        String tokenSecret = config.getString("im.token.secret", BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET);
        BootstrapSecurityChecks.requireSafeSecret(config, "im.token.secret", tokenSecret,
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET);
        JwtAuthenticator authenticator = new JwtAuthenticator(
                tokenSecret,
                new DbRefreshTokenStore(retryExecutor));
        IGroupManager groupManager = new CachedGroupManager(
                new DbGroupManager(retryExecutor),
                safeRedisCache(redisConfig, config, "im.cache.group-info-ttl-seconds", 120,
                        com.im.api.GroupInformation.class, "cache:group:info:", "group-info"),
                safeRedisCache(redisConfig, config, "im.cache.group-member-list-ttl-seconds", 30,
                        GroupMemberListSnapshot.class, "cache:group:members:", "group-member-list"),
                safeRedisCache(redisConfig, config, "im.cache.group-member-ids-ttl-seconds", 30,
                        GroupMemberIdsSnapshot.class, "cache:group:member-ids:", "group-member-ids"));
        IConversationManager conversationManager = new CachedConversationManager(
                new DbConversationManager(retryExecutor),
                safeRedisCache(redisConfig, config, "im.cache.conversation-list-ttl-seconds", 30,
                        ConversationListSnapshot.class, "cache:conversation:list:", "conversation-list"),
                safeRedisCache(redisConfig, config, "im.cache.conversation-item-ttl-seconds", 30,
                        com.im.api.Conversation.class, "cache:conversation:item:", "conversation-item"));
        IFriendManager friendManager = new DbFriendManager(retryExecutor);
        DbUserManager dbUserManager = new DbUserManager(retryExecutor, routeTable);
        IUserManager userManager = new CachedUserManager(
                dbUserManager,
                safeRedisCache(redisConfig, config, "im.cache.user-profile-ttl-seconds", 120,
                        com.im.api.UserInformation.class, "cache:user:profile:", "user-profile"));
        IPasswordHasher passwordHasher = new Pbkdf2PasswordHasher();
        return new BusinessDependencies(
                authenticator, retryExecutor, groupManager, conversationManager, friendManager,
                userManager, dbUserManager, passwordHasher);
    }

    private static <V> Cache<String, V> safeRedisCache(RedisConfiguration redisConfig,
                                                       Config config,
                                                       String ttlConfigKey,
                                                       long defaultTtlSeconds,
                                                       Class<V> valueType,
                                                       String keyPrefix,
                                                       String name) {
        Serializer<V, String> serializer = new JacksonSerializer<>();
        return new SafeCache<>(new RedisJsonCache<>(
                redisConfig.async(),
                Function.identity(),
                serializer,
                valueType,
                keyPrefix,
                Duration.ofSeconds(config.getLong(ttlConfigKey, defaultTtlSeconds))),
                name);
    }

    private static CallDependencies createCall(Config config, IMessageQueue messageQueue,
                                               IGroupManager groupManager,
                                               RedisConfiguration redisConfig) {
        ICallManager callManager = null;
        if (config.getBoolean("im.call.enabled", false)) {
            String sfuEndpoint = config.getString("im.call.sfu-endpoint", BootstrapDefaults.LIVEKIT_SFU_ENDPOINT);
            String apiKey = config.getString("im.call.api-key", BootstrapSecurityChecks.DEFAULT_CALL_API_KEY);
            String apiSecret = config.getString("im.call.api-secret", "");
            requireCallCredentials(config);
            callManager = new LiveKitCallManager(
                    apiKey,
                    apiSecret,
                    sfuEndpoint);
            log.info("LiveKitCallManager enabled: endpoint={}", sfuEndpoint);
            if (sfuEndpoint.contains(BootstrapDefaults.LOCALHOST_NAME)
                    || sfuEndpoint.contains(BootstrapDefaults.LOOPBACK_HOST)) {
                log.warn("[CALL] im.call.sfu-endpoint={} 是本机地址。" +
                         " 跨机器通话时其他用户会收到此地址但无法连接 LiveKit。" +
                         " 请将 im.call.sfu-endpoint 改为所有客户端都能访问的公网 IP 或域名，" +
                         " 例如 ws://192.168.x.x:7880 或 wss://im.example.com/livekit", sfuEndpoint);
            }
        }

        CallStateManager callStateManager = callManager != null
                ? new CallStateManager(messageQueue, new RedisSingleCallStateStore(redisConfig),
                config.getLong("im.call.timeout-seconds", 30))
                : null;
        GroupCallManager groupCallManager = callManager != null
                ? new GroupCallManager(groupManager, callManager, new RedisGroupCallStateStore(redisConfig,
                config.getString("im.call.group.redis-key-layout", "legacy")),
                config.getInt("im.call.group.max-participants", 16))
                : null;
        return new CallDependencies(callManager, callStateManager, groupCallManager);
    }

    static void requireCallCredentials(Config config) {
        if (!config.getBoolean("im.call.enabled", false)) {
            return;
        }
        String apiKey = config.getString("im.call.api-key", BootstrapSecurityChecks.DEFAULT_CALL_API_KEY);
        String apiSecret = config.getString("im.call.api-secret", "");
        BootstrapSecurityChecks.requireSafeSecret(config, "im.call.api-key", apiKey,
                BootstrapSecurityChecks.DEFAULT_CALL_API_KEY);
        BootstrapSecurityChecks.requireSafeSecret(config, "im.call.api-secret", apiSecret,
                BootstrapSecurityChecks.DEFAULT_CALL_API_SECRET, "");
    }

    private static void applyMultiLoginStrategy(Config config, SessionManager sessionManager) {
        String strategyName = config.getString("im.login.multi-strategy", BootstrapDefaults.MULTI_LOGIN_STRATEGY);
        try {
            MultiLoginStrategy strategy = MultiLoginStrategy.valueOf(strategyName);
            sessionManager.setLoginStrategy(strategy);
            log.info("Multi-login strategy set: {}", strategy);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid multi-login strategy '{}', using {}", strategyName, BootstrapDefaults.MULTI_LOGIN_STRATEGY);
        }
    }

}
