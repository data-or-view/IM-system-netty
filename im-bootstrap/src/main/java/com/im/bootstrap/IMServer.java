package com.im.bootstrap;

import com.im.api.*;
import com.im.api.cache.ICache;
import com.im.api.retry.RetryExecutor;
import com.im.bootstrap.http.*;
import com.im.core.auth.HmacTokenAuthenticator;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.config.*;
import com.im.core.conversation.DbConversationManager;
import com.im.core.conversation.LocalConversationManager;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.SchemaInitializer;
import com.im.core.delivery.*;
import com.im.core.discovery.*;
import com.im.core.dispatcher.MessageRouterHandler;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.file.MinioFileStorageService;
import com.im.core.friend.DbFriendManager;
import com.im.core.friend.LocalFriendManager;
import com.im.core.group.DbGroupManager;
import com.im.core.group.LocalGroupManager;
import com.im.core.handler.*;
import com.im.core.mq.MemoryMessageQueue;
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
import com.im.core.util.IMExecutors;
import com.im.core.webhook.LocalWebhookManager;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IMServer implements ILifecycle {

    private static final Logger log = LoggerFactory.getLogger(IMServer.class);

    private final ServerConfiguration config;
    private final SessionManager sessionManager;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;
    private final MessageRouterHandler routerHandler;
    private final ConnectionEventHandler connectionEventHandler;
    private final INodeDiscovery nodeDiscovery;
    private final IRouteTable routeTable;
    private final RedisConfiguration redisConfig;
    private final IClusterMessageBus clusterMessageBus;
    private final HttpRestHandler httpRestHandler;
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
    private static boolean databaseFailed = false;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel wsChannel;
    private Channel httpChannel;
    private ScheduledExecutorService scanScheduler;

    public IMServer(ServerConfiguration config, PropertySources props) {
        this.config = config;
        this.sessionManager = new SessionManager();
        this.pendingAcknowledgementManager = new PendingAcknowledgementManager();
        String nodeId = config.getNodeId();

        // 集群基础设施（Redis / Local）
        ClusterInfra infra = initClusterInfrastructure(props, nodeId);
        this.routeTable = infra.routeTable;
        this.redisConfig = infra.redisConfig;
        this.clusterMessageBus = infra.clusterMessageBus;
        this.nodeDiscovery = infra.nodeDiscovery;

        // 消息序号
        this.sequenceManager = redisConfig != null
                ? new RedisSequenceManager(redisConfig)
                : new LocalSequenceManager();

        // 认证 + 重试
        var authenticator = new HmacTokenAuthenticator(config.getTokenSecret());
        this.retryExecutor = new FailsafeRetryExecutor();

        // 业务 Manager（DB / 内存）
        this.groupManager = dbEnabled(config)
                ? new DbGroupManager(retryExecutor)
                : new LocalGroupManager(new ConcurrentHashCache<>(), new ConcurrentHashCache<>());
        this.conversationManager = dbEnabled(config)
                ? new DbConversationManager(retryExecutor)
                : new LocalConversationManager(new ConcurrentHashCache<>());
        this.friendManager = dbEnabled(config) ? new DbFriendManager(retryExecutor) : new LocalFriendManager();
        this.userManager = dbEnabled(config) ? new DbUserManager(retryExecutor) : new LocalUserManager();

        // 文件存储
        this.fileStorage = new MinioFileStorageService(
                nonNull(props.get("im.minio.endpoint"), "http://127.0.0.1:9000"),
                nonNull(props.get("im.minio.access-key"), props.get("MINIO_ACCESS_KEY"), "minioadmin"),
                nonNull(props.get("im.minio.secret-key"), props.get("MINIO_SECRET_KEY"), "minioadmin"));

        // 消息存储 + 队列
        this.messageStore = dbEnabled(config) ? new DbMessageStore(retryExecutor) : new LocalMessageStore();
        this.messageQueue = new MemoryMessageQueue();

        // HTTP REST handler
        HttpRestHandler hrh = new HttpRestHandler();
        new UserRestHandler(userManager).register(hrh);
        new FriendRestHandler(friendManager).register(hrh);
        new GroupRestHandler(groupManager).register(hrh);
        new ConversationRestHandler(conversationManager).register(hrh);
        new MessageRestHandler(messageStore, sequenceManager).register(hrh);
        new FileRestHandler(fileStorage).register(hrh);
        hrh.addInterceptor(new HttpRequestLogInterceptor());
        this.httpRestHandler = hrh;

        // 消费者
        this.persistenceConsumer = new PersistenceConsumer(messageQueue, messageStore, conversationManager);
        this.deliveryConsumer = new DeliveryConsumer(
                messageQueue, sessionManager, routeTable, clusterMessageBus, nodeId, groupManager);

        // 连接事件
        this.connectionEventHandler = new ConnectionEventHandler(
                sessionManager, pendingAcknowledgementManager, routeTable, nodeId);

        // Use Cases
        LoginUseCase loginUseCase = new LoginUseCase(authenticator, routeTable, messageStore, nodeId);
        WebhookService webhookService = new WebhookService(new LocalWebhookManager(config.getWebhookUrl()));
        SendMessageUseCase sendMessageUseCase = new SendMessageUseCase(
                messageQueue, messageStore, sequenceManager, groupManager, webhookService);

        // Handler 注册
        List<IMessageHandler> handlers = List.of(
                new HeartbeatHandler(new HeartbeatUseCase(routeTable), sessionManager),
                new LoginHandler(loginUseCase, sessionManager),
                new RegisterHandler(new RegisterUseCase(userManager)),
                new ChatHandler(sendMessageUseCase),
                new PullMessageHandler(new PullMessageUseCase(messageStore, sequenceManager)),
                new ConversationGetHandler(new ConversationGetUseCase(conversationManager)),
                new ConversationSetHandler(new ConversationSetUseCase(conversationManager)),
                new GroupHandler(new GroupUseCase(groupManager)),
                new GroupSearchHandler(new GroupSearchUseCase(groupManager)),
                new FriendHandler(new FriendUseCase(friendManager)),
                new UserSearchHandler(new UserSearchUseCase(userManager)),
                new FileUploadHandler(new FileUploadUseCase(fileStorage))
        );

        this.routerHandler = new MessageRouterHandler(handlers, config.getBusinessThreads());
        this.routerHandler.addInterceptor(new AuthenticationInterceptor(authenticator));
        this.routerHandler.addInterceptor(new AuthorizationInterceptor(authenticator));
    }

    // ── Cluster infrastructure setup ──

    private record ClusterInfra(IRouteTable routeTable, RedisConfiguration redisConfig,
                                 IClusterMessageBus clusterMessageBus, INodeDiscovery nodeDiscovery,
                                 IClusterStateStore stateStore) {}

    private ClusterInfra initClusterInfrastructure(PropertySources props, String nodeId) {
        String redisClusterNodes = nonNull(props.get("im.redis.cluster.nodes"), props.get("redis.cluster.nodes"));
        String redisHost = nonNull(props.get("im.redis.host"), props.get("redis.host"), config.getRedisHost());

        if (redisClusterNodes != null && !redisClusterNodes.isEmpty()) {
            config.setRedisHost("cluster");
        } else if (redisHost != null && !redisHost.isEmpty()) {
            config.setRedisHost(redisHost);
            String redisPort = nonNull(props.get("im.redis.port"), props.get("redis.port"), String.valueOf(config.getRedisPort()));
            if (redisPort != null) config.setRedisPort(Integer.parseInt(redisPort));
        }

        IRouteTable rt;
        RedisConfiguration rc = null;
        if (config.isRedisEnabled()) {
            RedisConfiguration.Builder rcb = RedisConfiguration.builder().password(config.getRedisPassword());
            if (redisClusterNodes != null && !redisClusterNodes.isEmpty()) {
                rcb.clusterNodes(redisClusterNodes.split(","));
            } else {
                rcb.host(config.getRedisHost()).port(config.getRedisPort()).database(config.getRedisDatabase());
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

        boolean useEpoll = config.isUseEpoll() && Epoll.isAvailable();
        bossGroup = useEpoll
                ? new EpollEventLoopGroup(config.getBossThreads())
                : new NioEventLoopGroup(config.getBossThreads());
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(config.getWorkerThreads())
                : new NioEventLoopGroup(config.getWorkerThreads());

        scanScheduler = IMExecutors.newScheduledExecutor("im-scanner", 1);
        scanScheduler.scheduleAtFixedRate(
                () -> sessionManager.scanIdleSessions(config.getHeartbeatTimeoutSeconds()),
                30, 30, TimeUnit.SECONDS);

        if (config.isWsEnabled()) {
            wsChannel = WsServerBootstrap.start(bossGroup, workerGroup, config.getWsPort(), useEpoll,
                    connectionEventHandler, routerHandler);
        }
        if (config.isHttpEnabled()) {
            httpChannel = HttpServerBootstrap.start(bossGroup, workerGroup, config.getHttpPort(), useEpoll,
                    httpRestHandler);
        }

        log.info("Server started: nodeId={}, WS={}, HTTP={}",
                config.getNodeId(),
                config.isWsEnabled() ? config.getWsPort() : "disabled",
                config.isHttpEnabled() ? config.getHttpPort() : "disabled");
    }

    @Override
    public void shutdown() throws Exception {
        log.info("Shutting down...");
        deliveryConsumer.shutdown();
        persistenceConsumer.shutdown();
        messageQueue.shutdown();
        clusterMessageBus.shutdown();
        nodeDiscovery.unregister();
        nodeDiscovery.shutdown();
        if (scanScheduler != null) scanScheduler.shutdown();
        routerHandler.shutdown();
        connectionEventHandler.shutdown();
        pendingAcknowledgementManager.shutdown();
        sessionManager.clear();
        if (redisConfig != null) redisConfig.close();
        if (wsChannel != null) wsChannel.close().sync();
        if (httpChannel != null) httpChannel.close().sync();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        log.info("Shutdown complete");
    }

    // ── 工具 ──

    private NodeInformation buildNodeInformation() {
        String host = "127.0.0.1";
        try { host = InetAddress.getLocalHost().getHostAddress(); } catch (Exception ignored) {}
        Map<String, String> attrs = new java.util.HashMap<>();
        int servicePort = config.isWsEnabled() ? config.getWsPort() : 0;
        attrs.put("webSocketPort", String.valueOf(servicePort));
        return new NodeInformation(config.getNodeId(), host, servicePort, attrs);
    }

    private static String nonNull(String... vals) {
        for (String v : vals) { if (v != null && !v.isEmpty()) return v; }
        return null;
    }

    private static boolean dbEnabled(ServerConfiguration config) {
        if (databaseFailed) return false;
        String dbProp = System.getProperty("im.db.enabled");
        if (dbProp == null) dbProp = System.getProperty("db.enabled");
        if ("true".equalsIgnoreCase(dbProp)) return true;
        String jdbcUrl = System.getProperty("im.db.jdbc-url");
        if (jdbcUrl == null) jdbcUrl = System.getProperty("db.jdbcUrl");
        return jdbcUrl != null && !jdbcUrl.isEmpty();
    }

    // ── 配置加载 ──

    static PropertySources loadPropertySources() {
        String activeEnv = System.getProperty("im.env");
        if (activeEnv == null || activeEnv.isBlank()) activeEnv = System.getenv("IM_ENV");
        PropertySources.Builder builder = PropertySources.builder()
                .add(new SystemPropertySource()).add(new EnvPropertySource());
        if (activeEnv != null && !activeEnv.isBlank())
            builder.add(new YamlPropertySource("config/application-" + activeEnv.trim() + ".yml", 150));
        return builder.add(new YamlPropertySource("config/application.yml")).build();
    }

    // ========== main ==========

    public static void main(String[] args) throws Exception {
        PropertySources props = loadPropertySources();
        props.logSources();

        // 数据库初始化
        String dbEnabled = nonNull(props.get("im.db.enabled"), props.get("db.enabled"));
        String jdbcUrl = nonNull(props.get("im.db.jdbc-url"), props.get("im.db.jdbcUrl"), props.get("db.jdbcUrl"));
        if ("true".equalsIgnoreCase(dbEnabled) || (jdbcUrl != null && !jdbcUrl.isEmpty())) {
            DatabaseConfiguration dbConfig = jdbcUrl != null && !jdbcUrl.isEmpty()
                    ? new DatabaseConfiguration.Builder()
                        .jdbcUrl(jdbcUrl)
                        .username(nonNull(props.get("im.db.username"), props.get("db.username"), "root"))
                        .password(nonNull(props.get("im.db.password"), props.get("db.password"), "password"))
                        .build()
                    : DatabaseConfiguration.develop();
            try {
                MyBatisPlusFactory.init(dbConfig);
                SchemaInitializer.initialize(MyBatisPlusFactory.getDataSource(),
                        nonNull(props.get("im.db.schema"), props.get("db.schema"), "auto"));
            } catch (Exception e) {
                log.error("Failed to initialize database, falling back to in-memory storage", e);
                databaseFailed = true;
            }
        } else {
            log.info("Database disabled (set im.db.enabled=true or db.enabled=true to enable)");
        }

        // 构建配置
        Properties serverProps = new Properties();
        for (String key : new String[]{
                "im.server.boss-threads", "im.server.worker-threads", "im.server.business-threads",
                "im.server.idle-timeout", "im.server.heartbeat-timeout", "im.server.max-frame-length",
                "im.server.socket-rcv-buf", "im.server.socket-snd-buf", "im.server.use-epoll",
                "im.node.id", "im.token.secret", "im.ws.port", "im.ws.enabled", "im.webhook.url",
                "im.http.port", "im.http.enabled",
                "im.redis.host", "im.redis.port", "im.redis.password", "im.redis.database"
        }) {
            String val = props.get(key);
            if (val != null) serverProps.setProperty(key, val);
        }
        if (args.length > 0) serverProps.setProperty("im.node.id", args[0]);

        IMServer server = new IMServer(ServerConfiguration.from(serverProps), props);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.shutdown(); } catch (Exception e) { log.error("Shutdown error", e); }
        }));
        server.start();
        log.info("Server ready. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
