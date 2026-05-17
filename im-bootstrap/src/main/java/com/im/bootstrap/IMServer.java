package com.im.bootstrap;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.retry.RetryExecutor;
import com.im.config.Config;
import com.im.config.ConfigLoader;
import com.im.config.YamlConfigSource;
import com.im.core.auth.HmacTokenAuthenticator;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.conversation.DbConversationManager;
import com.im.core.conversation.LocalConversationManager;
import com.im.core.conversation.RedisConversationManager;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.delivery.*;
import com.im.core.discovery.*;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.infrastructure.storage.file.MinioFileStorageService;
import com.im.infrastructure.storage.usecase.FileUploadUseCase;
import com.im.core.friend.DbFriendManager;
import com.im.core.friend.LocalFriendManager;
import com.im.core.group.DbGroupManager;
import com.im.core.group.LocalGroupManager;
import com.im.core.handler.*;
import com.im.core.handler.unified.*;
import com.im.core.mq.MemoryMessageQueue;
import com.im.core.mq.RedisMessageQueue;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.redis.RedisStateStore;
import com.im.core.retry.FailsafeRetryExecutor;
import com.im.core.seq.LocalSequenceManager;
import com.im.core.seq.RedisSequenceManager;
import com.im.core.session.SessionManager;
import com.im.core.store.DbMessageStore;
import com.im.core.store.LocalMessageStore;
import com.im.core.store.LocalStateStore;
import com.im.core.usecase.*;
import com.im.core.user.DbUserManager;
import com.im.core.user.LocalUserManager;
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
    private final ExecutorService virtualExecutor;
    private static boolean databaseFailed = false;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel wsChannel;
    private Channel httpChannel;
    private ScheduledExecutorService scanScheduler;

    public IMServer(Config config) {
        this.config = config;
        this.sessionManager = new SessionManager();
        applyMultiLoginStrategy(config);
        this.pendingAcknowledgementManager = new PendingAcknowledgementManager();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        String nodeId = config.getString("im.node.id", "node-1");

        // 集群基础设施（Redis / Local）
        ClusterInfra infra = initClusterInfrastructure(config, nodeId);
        this.routeTable = infra.routeTable;
        this.redisConfig = infra.redisConfig;
        this.clusterMessageBus = infra.clusterMessageBus;
        this.nodeDiscovery = infra.nodeDiscovery;

        // 消息序号
        this.sequenceManager = redisConfig != null
                ? new RedisSequenceManager(redisConfig)
                : new LocalSequenceManager();

        // 认证 + 重试
        var authenticator = new HmacTokenAuthenticator(
                config.getString("im.token.secret", "im-system-dev-secret-change-in-production"));
        this.retryExecutor = new FailsafeRetryExecutor();

        // 业务 Manager（DB / 内存）
        this.groupManager = dbEnabled()
                ? new DbGroupManager(retryExecutor)
                : new LocalGroupManager(new ConcurrentHashCache<>(), new ConcurrentHashCache<>());
        if (dbEnabled()) {
            this.conversationManager = new DbConversationManager(retryExecutor);
        } else if (redisConfig != null) {
            this.conversationManager = new RedisConversationManager(redisConfig);
        } else {
            this.conversationManager = new LocalConversationManager(new ConcurrentHashCache<>());
        }
        this.friendManager = dbEnabled() ? new DbFriendManager(retryExecutor) : new LocalFriendManager();
        this.userManager = dbEnabled() ? new DbUserManager(retryExecutor, routeTable) : new LocalUserManager(routeTable);

        // 文件存储
        this.fileStorage = new MinioFileStorageService(
                config.getString("im.minio.endpoint").orElse("http://127.0.0.1:9000"),
                config.getString("im.minio.access-key").orElse("minioadmin"),
                config.getString("im.minio.secret-key").orElse("minioadmin"));

        // 消息存储 + 队列
        this.messageStore = dbEnabled() ? new DbMessageStore(retryExecutor) : new LocalMessageStore();
        this.messageQueue = redisConfig != null
                ? new RedisMessageQueue(redisConfig, nodeId)
                : new MemoryMessageQueue();

        // 消费者
        this.persistenceConsumer = new PersistenceConsumer(messageQueue, messageStore, conversationManager, groupManager);
        this.deliveryConsumer = new DeliveryConsumer(
                messageQueue, sessionManager, routeTable, clusterMessageBus, nodeId, groupManager);

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
        dispatcher.addInterceptor(new AuthInterceptor(authenticator));

        // 注册 handler（使用 Operation 枚举，路由+认证元数据由枚举统一管理）
        dispatcher.registerHandlers(new com.im.core.handler.unified.UserHandler(userManager),
                Operation.USER_REGISTER, Operation.USER_INFO, Operation.USER_SEARCH, Operation.USER_UPDATE);
        dispatcher.registerHandlers(new com.im.core.handler.unified.FriendHandler(friendManager),
                Operation.FRIEND_APPLY, Operation.FRIEND_APPROVE, Operation.FRIEND_REMOVE, Operation.FRIEND_LIST,
                Operation.FRIEND_BLACK, Operation.FRIEND_UNBLACK, Operation.FRIEND_BLACKLIST);
        dispatcher.registerHandlers(new com.im.core.handler.unified.GroupHandler(groupManager),
                Operation.GROUP_CREATE, Operation.GROUP_JOIN, Operation.GROUP_QUIT, Operation.GROUP_KICK,
                Operation.GROUP_DISBAND, Operation.GROUP_INFO_UPDATE, Operation.GROUP_INFO,
                Operation.GROUP_SEARCH, Operation.GROUP_MEMBERS);
        dispatcher.registerHandlers(new com.im.core.handler.unified.ConversationHandler(conversationManager),
                Operation.CONVERSATION_LIST, Operation.CONVERSATION_SET, Operation.CONVERSATION_READ);
        dispatcher.registerHandlers(new com.im.core.handler.unified.MessageHandler(messageStore, sequenceManager),
                Operation.CHAT_PULL, Operation.CHAT_SEQ, Operation.CHAT_SYNC);
        dispatcher.registerHandler(Operation.CHAT_SEND, new com.im.core.handler.unified.ChatHandler(sendMessageUseCase));
        dispatcher.registerHandler(Operation.CHAT_SEND_GROUP, new com.im.core.handler.unified.ChatHandler(sendMessageUseCase));

        // 撤回
        RevokeUseCase revokeUseCase = new RevokeUseCase(messageStore, groupManager);
        dispatcher.registerHandler(Operation.CHAT_REVOKE, new com.im.core.handler.unified.RevokeHandler(revokeUseCase, sessionManager));

        dispatcher.registerHandler(Operation.LOGIN, new LoginHandler(loginUseCase, sessionManager, routeTable, nodeId));
        dispatcher.registerHandler(Operation.REGISTER, new RegisterHandler(new RegisterUseCase(userManager)));
        dispatcher.registerHandler(Operation.HEARTBEAT, new HeartbeatHandler(new HeartbeatUseCase(routeTable), sessionManager));
        dispatcher.registerHandler(Operation.FILE_UPLOAD, new com.im.core.handler.unified.FileUploadHandler(new FileUploadUseCase(fileStorage)));
    }

    // ── Cluster infrastructure setup ──

    private record ClusterInfra(IRouteTable routeTable, RedisConfiguration redisConfig,
                                 IClusterMessageBus clusterMessageBus, INodeDiscovery nodeDiscovery,
                                 IClusterStateStore stateStore) {}

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

    private ClusterInfra initClusterInfrastructure(Config config, String nodeId) {
        IRouteTable rt;
        RedisConfiguration rc = null;

        String redisHost = config.getString("im.redis.host").orElse(null);
        if (redisHost != null && !redisHost.isEmpty()) {
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
            rc = rcb.build();
            rt = new RedisRouteTable(rc, sessionManager, nodeId);
        } else {
            rt = new LocalRouteTable(sessionManager, nodeId);
        }

        return new ClusterInfra(
                rt, rc,
                rc != null ? new RedisClusterMessageBus(rc, nodeId) : new LocalClusterMessageBus(),
                rc != null ? new RedisNodeDiscovery(rc) : new LocalNodeDiscovery(),
                rc != null ? new RedisStateStore(rc) : new LocalStateStore());
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

    // ── 配置加载 ──

    static Config loadConfig() {
        String activeEnv = System.getProperty("im.env");
        if (activeEnv == null || activeEnv.isBlank()) activeEnv = System.getenv("IM_ENV");
        if (activeEnv != null && !activeEnv.isBlank()) {
            // order=1 优先级高于内置的 classpath:application.yml (order=2)
            ConfigLoader.register(new YamlConfigSource("classpath:application-" + activeEnv.trim() + ".yml", 1));
        }
        // 内置 classpath:application.yml 由 ConfigLoader.doLoad() 自动加载
        return ConfigLoader.load();
    }

    // ========== main ==========

    public static void main(String[] args) throws Exception {
        Config config = loadConfig();

        // 数据库初始化（仅在 im.db.enabled=true 时启动）
        if ("true".equalsIgnoreCase(config.getString("im.db.enabled").orElse("false"))) {
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
            } catch (Exception e) {
                log.error("Failed to initialize database, falling back to in-memory storage", e);
                databaseFailed = true;
            }
        } else {
            log.info("Database disabled (set im.db.enabled=true to enable)");
        }

        // 节点 ID（命令行参数覆盖）
        String nodeId = config.getString("im.node.id", "node-1");
        if (args.length > 0) nodeId = args[0];

        IMServer server = new IMServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
        }));
        server.start();
        log.info("Server ready. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
