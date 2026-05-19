package com.im.bootstrap;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.retry.RetryExecutor;
import com.im.config.Config;
import com.im.core.auth.JwtAuthenticator;
import com.im.core.call.CallStateManager;
import com.im.core.call.LiveKitCallManager;
import com.im.core.conversation.DbConversationManager;
import com.im.core.delivery.ClusterDeliveryHandler;
import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.delivery.RedisClusterMessageBus;
import com.im.core.discovery.RedisNodeDiscovery;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.infrastructure.storage.file.MinioFileStorageService;
import com.im.infrastructure.storage.usecase.FileUploadUseCase;
import com.im.infrastructure.storage.usecase.MultipartUploadUseCase;
import com.im.core.friend.DbFriendManager;
import com.im.core.group.DbGroupManager;
import com.im.core.handler.*;
import com.im.core.handler.unified.*;
import com.im.core.mq.RedisMessageQueue;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.retry.FailsafeRetryExecutor;
import com.im.core.seq.RedisSequenceManager;
import com.im.core.session.RedisSessionManager;
import com.im.core.session.SessionManager;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.store.DbMessageStore;
import com.im.core.usecase.*;
import com.im.core.user.DbUserManager;
import com.im.common.util.IMExecutors;
import com.im.core.webhook.LocalWebhookManager;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IMServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(IMServer.class);

    private final Config config;
    private final SessionManager sessionManager;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;
    private final ApiDispatcher dispatcher;
    private final ConnectionEventHandler connectionEventHandler;
    private final INodeDiscovery nodeDiscovery;
    private final IRouteTable routeTable;
    private final RedisConfiguration redisConfig;
    private final IClusterMessageBus clusterMessageBus;
    private final ISequenceManager sequenceManager;
    private final RetryExecutor retryExecutor;
    private final IGroupManager groupManager;
    private final IConversationManager conversationManager;
    private final IFriendManager friendManager;
    private final IUserManager userManager;
    private final IMessageQueue messageQueue;
    private final PersistenceConsumer persistenceConsumer;
    private final DeliveryConsumer deliveryConsumer;
    private final IMessageStore messageStore;
    private final IFileStorageService fileStorage;
    private CallStateManager callStateManager;
    private final ExecutorService virtualExecutor;
    private static boolean databaseFailed = false;

    static void markDatabaseFailed() {
        databaseFailed = true;
    }

    static void resetDatabaseFailed() {
        databaseFailed = false;
    }

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel wsChannel;
    private Channel httpChannel;
    private ScheduledExecutorService scanScheduler;

    public IMServer(Config config) {
        this.config = config;
        this.redisConfig = buildRedisConfig(config);
        if (this.redisConfig == null) {
            throw new IllegalStateException(
                    "Cluster mode requires Redis. Set im.redis.host (and im.db.enabled=true).");
        }
        if (!dbEnabled()) {
            throw new IllegalStateException(
                    "Cluster mode requires database. Set im.db.enabled=true and initialize schema.");
        }
        initDatabase();
        this.sessionManager = new RedisSessionManager(redisConfig);
        applyMultiLoginStrategy(config);
        this.pendingAcknowledgementManager = new PendingAcknowledgementManager();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        String nodeId = config.getString("im.node.id", "node-1");

        // 集群基础设施
        ClusterInfra infra = initClusterInfrastructure(config, redisConfig, nodeId);
        this.routeTable = infra.routeTable;
        this.clusterMessageBus = infra.clusterMessageBus;
        this.nodeDiscovery = infra.nodeDiscovery;

        // 消息序号
        this.sequenceManager = new RedisSequenceManager(redisConfig);

        // 认证 + 重试
        var authenticator = new JwtAuthenticator(
                config.getString("im.token.secret", "im-system-dev-secret-change-in-production"));
        this.retryExecutor = new FailsafeRetryExecutor();

        // 业务 Manager（集群模式：必须 DB）
        this.groupManager = new DbGroupManager(retryExecutor);
        this.conversationManager = new DbConversationManager(retryExecutor);
        this.friendManager = new DbFriendManager(retryExecutor);
        this.userManager = new DbUserManager(retryExecutor, routeTable);

        // 文件存储
        this.fileStorage = new MinioFileStorageService(
                config.getString("im.minio.endpoint").orElse("http://127.0.0.1:9000"),
                config.getString("im.minio.access-key").orElse("minioadmin"),
                config.getString("im.minio.secret-key").orElse("minioadmin"));

        // RTC 通话（LiveKit）
        ICallManager callManager = null;
        if (config.getBoolean("im.call.enabled", false)) {
            callManager = new LiveKitCallManager(
                    config.getString("im.call.api-key", "devkey"),
                    config.getString("im.call.api-secret", ""),
                    config.getString("im.call.sfu-endpoint", "ws://localhost:7880"));
            log.info("LiveKitCallManager enabled: endpoint={}", config.getString("im.call.sfu-endpoint"));
        }

        // 消息存储 + 队列（集群模式：必须 DB + Redis）
        this.messageStore = new DbMessageStore(retryExecutor);
        this.messageQueue = new RedisMessageQueue(redisConfig, nodeId);

        // 通话超时管理（依赖 messageQueue，需在其之后初始化）
        this.callStateManager = (callManager != null)
                ? new CallStateManager(messageQueue, config.getLong("im.call.timeout-seconds", 30))
                : null;

        // 消费者
        this.persistenceConsumer = new PersistenceConsumer(messageQueue, messageStore, conversationManager, groupManager);
        this.deliveryConsumer = new DeliveryConsumer(
                messageQueue, sessionManager, routeTable, clusterMessageBus, nodeId, groupManager);

        // 注册集群消息处理器（接收远端节点转发来的消息，投递到本地 session）
        ClusterDeliveryHandler clusterDeliveryHandler = new ClusterDeliveryHandler(sessionManager);
        clusterMessageBus.subscribe("SINGLE_CHAT", clusterDeliveryHandler);
        clusterMessageBus.subscribe("GROUP_CHAT", clusterDeliveryHandler);

        // 连接事件
        this.connectionEventHandler = new ConnectionEventHandler(
                sessionManager, pendingAcknowledgementManager, routeTable, nodeId);

        // Use Cases
        LoginUseCase loginUseCase = new LoginUseCase(authenticator, messageStore);
        WebhookService webhookService = new WebhookService(new LocalWebhookManager(
                config.getString("im.webhook.url").orElse("")));
        SendMessageUseCase sendMessageUseCase = new SendMessageUseCase(
                messageQueue, sequenceManager, groupManager, webhookService);

        // ── 统一 ApiDispatcher ──
        this.dispatcher = new ApiDispatcher();
        dispatcher.addInterceptor(new com.im.core.handler.unified.TelemetryInterceptor());
        dispatcher.addInterceptor(new AuthInterceptor(authenticator));

        // 注册 handler（使用 Operation 枚举，路由+认证元数据由枚举统一管理）
        dispatcher.registerHandlers(new com.im.core.handler.unified.UserHandler(userManager),
                Operation.USER_REGISTER, Operation.USER_INFO, Operation.USER_SEARCH, Operation.USER_UPDATE);
        dispatcher.registerHandlers(new com.im.core.handler.unified.FriendHandler(friendManager),
                Operation.FRIEND_APPLY, Operation.FRIEND_APPROVE, Operation.FRIEND_REMOVE, Operation.FRIEND_LIST,
                Operation.FRIEND_BLACK, Operation.FRIEND_UNBLACK, Operation.FRIEND_BLACKLIST,
                Operation.FRIEND_APPLY_SENT, Operation.FRIEND_APPLY_DETAIL, Operation.FRIEND_APPLY_UNHANDLED_COUNT);
        dispatcher.registerHandlers(new com.im.core.handler.unified.GroupHandler(groupManager),
                Operation.GROUP_CREATE, Operation.GROUP_JOIN, Operation.GROUP_QUIT, Operation.GROUP_KICK,
                Operation.GROUP_DISBAND, Operation.GROUP_INFO_UPDATE, Operation.GROUP_INFO,
                Operation.GROUP_SEARCH, Operation.GROUP_MEMBERS, Operation.GROUP_MUTE_ALL);
        dispatcher.registerHandlers(new com.im.core.handler.unified.ConversationHandler(conversationManager),
                Operation.CONVERSATION_LIST, Operation.CONVERSATION_SET, Operation.CONVERSATION_READ);
        dispatcher.registerHandlers(new com.im.core.handler.unified.MessageHandler(messageStore, sequenceManager),
                Operation.CHAT_PULL, Operation.CHAT_SEQ, Operation.CHAT_SYNC, Operation.CHAT_SEARCH);
        var chatHandler = new com.im.core.handler.unified.ChatHandler(sendMessageUseCase, callManager, this.callStateManager);
        dispatcher.registerHandler(Operation.CHAT_SEND, chatHandler);
        dispatcher.registerHandler(Operation.CHAT_SEND_GROUP, chatHandler);

        // 撤回
        RevokeUseCase revokeUseCase = new RevokeUseCase(messageStore, groupManager);
        dispatcher.registerHandler(Operation.CHAT_REVOKE, new com.im.core.handler.unified.RevokeHandler(revokeUseCase, sessionManager));

        dispatcher.registerHandler(Operation.LOGIN, new LoginHandler(loginUseCase, sessionManager, routeTable, nodeId));
        dispatcher.registerHandler(Operation.REGISTER, new RegisterHandler(new RegisterUseCase(userManager)));
        dispatcher.registerHandler(Operation.HEARTBEAT, new HeartbeatHandler(new HeartbeatUseCase(routeTable), sessionManager, authenticator));
        dispatcher.registerHandler(Operation.FILE_UPLOAD, new com.im.core.handler.unified.FileUploadHandler(
                new FileUploadUseCase(fileStorage, config.getLong("im.minio.max-file-size", 100L * 1024 * 1024))));
        dispatcher.registerHandlers(new com.im.core.handler.unified.FileMultipartHandler(
                        new MultipartUploadUseCase(fileStorage)),
                Operation.FILE_MULTIPART_INIT, Operation.FILE_MULTIPART_UPLOAD,
                Operation.FILE_MULTIPART_COMPLETE, Operation.FILE_MULTIPART_ABORT);
    }

    // ── Cluster infrastructure setup ──

    private record ClusterInfra(IRouteTable routeTable,
                                 IClusterMessageBus clusterMessageBus, INodeDiscovery nodeDiscovery) {}

    private void applyMultiLoginStrategy(Config config) {
        String strategyName = config.getString("im.login.multi-strategy", "ALLOW_MULTIPLE");
        try {
            MultiLoginStrategy strategy = MultiLoginStrategy.valueOf(strategyName);
            sessionManager.setLoginStrategy(strategy);
            log.info("Multi-login strategy set: {}", strategy);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid multi-login strategy '{}', using ALLOW_MULTIPLE", strategyName);
        }
    }

    private ClusterInfra initClusterInfrastructure(Config config, RedisConfiguration rc, String nodeId) {
        return new ClusterInfra(
                new RedisRouteTable(rc, sessionManager, nodeId),
                new RedisClusterMessageBus(rc, nodeId),
                new RedisNodeDiscovery(rc));
    }

    private RedisConfiguration buildRedisConfig(Config config) {
        String redisHost = config.getString("im.redis.host").orElse(null);
        if (redisHost == null || redisHost.isEmpty()) return null;

        int redisPort = config.getInt("im.redis.port", 6379);
        String redisPassword = config.getString("im.redis.password").orElse("");
        int redisDatabase = config.getInt("im.redis.database", 0);
        String redisClusterNodes = config.getString("im.redis.cluster.nodes").orElse(null);

        RedisConfiguration.Builder rcb = RedisConfiguration.builder()
                .password(redisPassword)
                .database(redisDatabase);
        if (redisClusterNodes != null && !redisClusterNodes.isEmpty()) {
            rcb.clusterNodes(redisClusterNodes.split(","));
        } else {
            rcb.host(redisHost).port(redisPort);
        }
        return rcb.build();
    }

    private void initDatabase() {
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
            markDatabaseFailed();
            throw new IllegalStateException("Database initialization failed", e);
        }
    }

    // ── Lifecycle ──

    @Override
    public void start() throws Exception {
        nodeDiscovery.start();
        nodeDiscovery.register(buildNodeInformation());
        clusterMessageBus.start();
        messageQueue.start();
        persistenceConsumer.start();
        deliveryConsumer.start();

        boolean useEpoll = config.getBoolean("im.server.use-epoll", true) && Epoll.isAvailable();
        bossGroup = useEpoll
                ? new EpollEventLoopGroup(config.getInt("im.server.boss-threads", 1))
                : new NioEventLoopGroup(config.getInt("im.server.boss-threads", 1));
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(config.getInt("im.server.worker-threads", 0))
                : new NioEventLoopGroup(config.getInt("im.server.worker-threads", 0));

        scanScheduler = IMExecutors.newScheduledExecutor("im-scanner", 1);
        scanScheduler.scheduleAtFixedRate(
                () -> sessionManager.scanIdleSessions(config.getInt("im.server.heartbeat-timeout", 120)),
                30, 30, TimeUnit.SECONDS);

        if (config.getBoolean("im.ws.enabled", true)) {
            wsChannel = WsServerBootstrap.start(bossGroup, workerGroup,
                    config.getInt("im.ws.port", 8081), useEpoll,
                    connectionEventHandler, dispatcher, virtualExecutor);
        }
        if (config.getBoolean("im.http.enabled", true)) {
            httpChannel = HttpServerBootstrap.start(bossGroup, workerGroup,
                    config.getInt("im.http.port", 8082), useEpoll,
                    new com.im.bootstrap.http.HttpRequestAdapter(dispatcher, virtualExecutor));
        }

        log.info("Server started: nodeId={}, WS={}, HTTP={}",
                config.getString("im.node.id", "node-1"),
                config.getBoolean("im.ws.enabled", true) ? config.getInt("im.ws.port", 8081) : "disabled",
                config.getBoolean("im.http.enabled", true) ? config.getInt("im.http.port", 8082) : "disabled");
    }

    @Override
    public void stop() {
        log.info("Shutting down...");
        deliveryConsumer.stop();
        persistenceConsumer.stop();
        messageQueue.stop();
        clusterMessageBus.stop();
        nodeDiscovery.unregister();
        nodeDiscovery.stop();
        if (scanScheduler != null) scanScheduler.shutdown();
        connectionEventHandler.shutdown();
        if (callStateManager != null) callStateManager.shutdown();
        pendingAcknowledgementManager.shutdown();
        sessionManager.clear();
        if (redisConfig != null) redisConfig.close();
        try {
            if (wsChannel != null) wsChannel.close().sync();
            if (httpChannel != null) httpChannel.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing channels");
        }
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (virtualExecutor != null) virtualExecutor.shutdown();
        log.info("Shutdown complete");
    }

    // ── 工具 ──

    private NodeInformation buildNodeInformation() {
        String host = "127.0.0.1";
        try { host = InetAddress.getLocalHost().getHostAddress(); } catch (Exception ignored) {}
        Map<String, String> attrs = new java.util.HashMap<>();
        int servicePort = config.getBoolean("im.ws.enabled", true) ? config.getInt("im.ws.port", 8081) : 0;
        attrs.put("webSocketPort", String.valueOf(servicePort));
        return new NodeInformation(
                config.getString("im.node.id", "node-1"), host, servicePort, attrs);
    }

    private boolean dbEnabled() {
        if (databaseFailed) return false;
        return config.getBoolean("im.db.enabled").orElse(false);
    }
}
