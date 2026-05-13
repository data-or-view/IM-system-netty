package com.im.bootstrap;

/**
 * IM 系统主启动入口。
 *
 * <p>装配并启动 Netty TCP (8080) 和 WebSocket (8081) 双端口服务器。
 * 在一个单体内完成所有组件的依赖注入和生命周期管理。</p>
 *
 * <h3>启动流程</h3>
 * <ol>
 *   <li>初始化组件：会话管理、路由表、节点发现、认证、业务 Manager、MQ 和存储</li>
 *   <li>启动 Netty 双端口（TCP + WebSocket），共享 EventLoopGroup 和业务 Handler</li>
 *   <li>MQ Consumer 开始消费消息（持久化 + 投递）</li>
 * </ol>
 *
 * <h3>关闭流程（优雅）</h3>
 * <ol>
 *   <li>停止 MQ Consumer</li>
 *   <li>关闭 EventLoopGroup</li>
 *   <li>等待虚拟线程池完成运行中任务</li>
 * </ol>
 *
 * <h3>配置</h3>
 * 配置项参考 {@link ServerConfiguration}，通过 Maven + ServerConfiguration 类加载。
 *
 * @see ServerConfiguration
 * @see ChatHandler
 * @see MessageRouterHandler
 */

import com.im.api.*;
import com.im.api.cache.ICache;
import com.im.bootstrap.ws.ByteBufToWebSocketHandler;
import com.im.bootstrap.ws.WebSocketIMDecoder;
import com.im.codec.IMDecoder;
import com.im.codec.IMEncoder;
import com.im.core.PendingAcknowledgementManager;
import com.im.core.auth.HmacTokenAuthenticator;
import com.im.core.cache.ConcurrentHashCache;
import com.im.core.conversation.LocalConversationManager;
import com.im.core.group.DbGroupManager;
import com.im.core.group.LocalGroupManager;
import com.im.core.handler.AuthenticationInterceptor;
import com.im.core.webhook.LocalWebhookManager;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.db.MyBatisPlusFactory;
import com.im.core.db.DatabaseConfiguration;
import com.im.core.friend.DbFriendManager;
import com.im.core.friend.LocalFriendManager;
import com.im.core.user.DbUserManager;
import com.im.core.user.LocalUserManager;
import com.im.core.file.MinioFileStorageService;
import com.im.core.handler.FileUploadHandler;
import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.LocalClusterMessageBus;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.discovery.LocalNodeDiscovery;
import com.im.core.discovery.LocalRouteTable;
import com.im.core.dispatcher.MessageRouterHandler;
import com.im.core.handler.*;
import com.im.core.mq.MemoryMessageQueue;
import com.im.core.seq.LocalSequenceManager;
import com.im.core.session.SessionManager;
import com.im.core.store.LocalMessageStore;
import com.im.core.util.IMExecutors;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IM 服务端启动器。
 *
 * Pipeline：
 *   LoggingHandler → IMDecoder → IMEncoder → IdleStateHandler → ConnectionEventHandler → MessageRouterHandler
 *
 * 集群架构（单机模式）：
 *   LocalNodeDiscovery（自身节点发现）
 *   LocalRouteTable（本地路由表→SessionManager）
 *
 * 消息管道：
 *   ChatHandler (Receiver) ──MQ──► DeliveryConsumer ──► writeAndFlush / store
 *
 * 登录/断开流程（路由表同步）：
 *   LoginHandler.handle → routeTable.online(userId)
 *   ConnectionEventHandler.channelInactive → routeTable.offline(userId)
 *
 * TODO: 生产环境替换为：
 *   · LocalNodeDiscovery → RedisNodeDiscovery / EtcdNodeDiscovery
 *   · LocalRouteTable → RedisRouteTable（在线状态 + 集群路由）
 *   · MemoryMessageQueue → KafkaQueue / RocketMQQueue
 */
public class IMServer implements ILifecycle {

    private static final Logger log = LoggerFactory.getLogger(IMServer.class);

    private final ServerConfiguration config;
    private final SessionManager sessionManager;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;
    private final MessageRouterHandler routerHandler;
    private final ConnectionEventHandler connectionEventHandler;

    // 集群基础设施
    private final INodeDiscovery nodeDiscovery;
    private final IRouteTable routeTable;
    private final RedisConfiguration redisConfig;
    private final IClusterMessageBus clusterMessageBus;

    // 消息序号
    private final LocalSequenceManager sequenceManager;

    // 认证
    private final HmacTokenAuthenticator authenticator;

    // 群聊
    private final IGroupManager groupManager;

    // 会话管理
    private final IConversationManager conversationManager;

    // 好友管理
    private final IFriendManager friendManager;

    // 用户管理
    private final IUserManager userManager;

    // 消息管道
    private final IMessageQueue messageQueue;
    private final PersistenceConsumer persistenceConsumer;
    private final DeliveryConsumer deliveryConsumer;
    private final IMessageStore messageStore;

    // Webhook
    private final IWebhookManager webhookManager;
    private final IFileStorageService fileStorage;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Channel wsChannel;
    private ScheduledExecutorService scanScheduler;

    public IMServer(ServerConfiguration config) {
        this.config = config;
        this.sessionManager = new SessionManager();
        this.pendingAcknowledgementManager = new PendingAcknowledgementManager();

        // ── 本节点标识 ──
        String nodeId = config.getNodeId();

        // ── 集群基础设施（单机模式：本地实现；开启 Redis 配置后自动使用 RedisRouteTable） ──
        this.nodeDiscovery = new LocalNodeDiscovery();

        // 支持 -Dredis.host 系统属性临时启用 Redis（开发测试用）
        String redisProp = System.getProperty("redis.host");
        if (redisProp != null && !redisProp.isEmpty()) {
            config.setRedisHost(redisProp);
            String redisPort = System.getProperty("redis.port");
            if (redisPort != null && !redisPort.isEmpty()) config.setRedisPort(Integer.parseInt(redisPort));
        }
        // 支持 -Dredis.cluster.nodes="127.0.0.1:6379,127.0.0.1:6380,127.0.0.1:6381" 集群模式
        String clusterProp = System.getProperty("redis.cluster.nodes");
        if (clusterProp != null && !clusterProp.isEmpty() && !config.isRedisEnabled()) {
            config.setRedisHost("cluster"); // 标记启用 Redis（实际使用 cluster nodes）
        }

        if (config.isRedisEnabled()) {
            RedisConfiguration.Builder rcb = RedisConfiguration.builder()
                    .password(config.getRedisPassword());
            if (clusterProp != null && !clusterProp.isEmpty()) {
                String[] nodes = clusterProp.split(",");
                rcb.clusterNodes(nodes);
                log.info("Using RedisClusterNodes: {}", clusterProp);
            } else {
                rcb.host(config.getRedisHost()).port(config.getRedisPort()).database(config.getRedisDatabase());
            }
            RedisConfiguration redisConfig = rcb.build();
            this.routeTable = new RedisRouteTable(redisConfig, sessionManager, nodeId);
            log.info("Using RedisRouteTable: {}:{}", config.getRedisHost(), config.getRedisPort());
        } else {
            this.routeTable = new LocalRouteTable(sessionManager, nodeId);
            log.info("Using LocalRouteTable (no Redis configured)");
        }
        this.redisConfig = routeTable instanceof RedisRouteTable ? ((RedisRouteTable) routeTable).getRedisConfig() : null;
        this.clusterMessageBus = new LocalClusterMessageBus();

        // ── 消息序号 ──
        this.sequenceManager = new LocalSequenceManager();

        // ── 认证 ──
        this.authenticator = new HmacTokenAuthenticator(config.getTokenSecret());

        // ── 群聊（带缓存） ──
        ICache<String, com.im.api.GroupInformation> groupInfoCache = new ConcurrentHashCache<>();
        ICache<String, List<String>> groupMemberCache = new ConcurrentHashCache<>();
        // 数据库模式下 DbGroupManager 自己管理数据，不使用缓存层
        this.groupManager = dbEnabled(config)
                ? new DbGroupManager()
                : new LocalGroupManager(groupInfoCache, groupMemberCache);

        // ── 会话管理（带缓存） ──
        ICache<String, List<Conversation>> conversationCache = new ConcurrentHashCache<>();
        this.conversationManager = new LocalConversationManager(conversationCache);

        // ── 数据库管理器（优先数据库实现，降级到内存） ──
        // 启动时 -Ddb.enabled=true 或指定 -Ddb.jdbcUrl 启用数据库模式
        this.friendManager = dbEnabled(config) ? new DbFriendManager() : new LocalFriendManager();
        this.userManager = dbEnabled(config) ? new DbUserManager() : new LocalUserManager();
        log.info("FriendManager: {} / UserManager: {}",
                dbEnabled(config) ? "DbFriendManager" : "LocalFriendManager",
                dbEnabled(config) ? "DbUserManager" : "LocalUserManager");

        // ── Webhook ──
        this.webhookManager = new LocalWebhookManager(config.getWebhookUrl());

        // ── 文件存储 ──
        this.fileStorage = new MinioFileStorageService();
        log.info("FileStorage: MinIO (bucket=im-system, endpoint={})",
                System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000"));

        // ── 消息基础设施 ──
        this.messageStore = new LocalMessageStore();
        this.messageQueue = new MemoryMessageQueue();

        // ── 消息消费者 ──
        this.persistenceConsumer = new PersistenceConsumer(messageQueue, messageStore, conversationManager);
        this.deliveryConsumer = new DeliveryConsumer(
                messageQueue, sessionManager, routeTable,
                clusterMessageBus, nodeId, groupManager);

        // ── 连接事件处理器 ──
        this.connectionEventHandler = new ConnectionEventHandler(
                sessionManager, pendingAcknowledgementManager, routeTable, nodeId);

        // ── 注册 IMessageHandler ──
        List<IMessageHandler> handlers = List.of(
                new HeartbeatHandler(sessionManager, routeTable),
                new LoginHandler(sessionManager, messageStore, routeTable, nodeId, authenticator, userManager),
                new RegisterHandler(userManager),
                ChatHandler.builder(messageQueue, messageStore, sequenceManager)
                        .groupManager(groupManager)
                        .webhookManager(webhookManager)
                        .build(),
                new PullMessageHandler(messageStore, sequenceManager),
                new ConversationGetHandler(conversationManager),
                new ConversationSetHandler(conversationManager),
                new GroupHandler(groupManager),
                new GroupSearchHandler(groupManager),
                new FriendHandler(friendManager),
                new UserSearchHandler(userManager),
                new FileUploadHandler(fileStorage)
        );

        this.routerHandler = new MessageRouterHandler(handlers, config.getBusinessThreads());
        // ── 注册认证拦截器（第一个执行，优先验证 token） ──
        this.routerHandler.addInterceptor(new AuthenticationInterceptor(authenticator));
        this.routerHandler.addInterceptor(new AuthorizationInterceptor(authenticator));
    }

    @Override
    public void start() throws Exception {
        // 1. 节点注册
        nodeDiscovery.start();
        nodeDiscovery.register(buildNodeInformation());

        // 2. 启动 MQ + 消费者
        messageQueue.start();
        persistenceConsumer.start();
        deliveryConsumer.start();

        // 3. EventLoopGroup
        boolean useEpoll = config.isUseEpoll() && Epoll.isAvailable();
        bossGroup = useEpoll
                ? new EpollEventLoopGroup(config.getBossThreads())
                : new NioEventLoopGroup(config.getBossThreads());
        workerGroup = useEpoll
                ? new EpollEventLoopGroup(config.getWorkerThreads())
                : new NioEventLoopGroup(config.getWorkerThreads());

        // 4. 空闲 session 定时扫描
        scanScheduler = IMExecutors.newScheduledExecutor("im-scanner", 1);
        scanScheduler.scheduleAtFixedRate(
                () -> sessionManager.scanIdleSessions(config.getHeartbeatTimeoutSeconds()),
                30, 30, TimeUnit.SECONDS);

        // 5. TCP ServerBootstrap
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, false)
                .childOption(ChannelOption.SO_RCVBUF, config.getSocketRcvBufSize())
                .childOption(ChannelOption.SO_SNDBUF, config.getSocketSndBufSize())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new LoggingHandler(LogLevel.INFO));
                        p.addLast(new IMDecoder());
                        p.addLast(new IMEncoder());
                        p.addLast(new IdleStateHandler(0, 0, config.getIdleTimeSeconds()));
                        p.addLast(connectionEventHandler);
                        p.addLast(routerHandler);
                    }
                });

        // 6. 绑定 TCP
        ChannelFuture future = bootstrap.bind(config.getPort()).sync();
        serverChannel = future.channel();

        log.info("TCP server started: nodeId={}, port={}, workers={}, useEpoll={}",
                config.getNodeId(), config.getPort(), config.getWorkerThreads(), useEpoll);

        // 7. WebSocket ServerBootstrap（同一 EventLoopGroup 共享）
        if (config.isWsEnabled()) {
            ByteBufToWebSocketHandler byteBufToWebSocketHandler = new ByteBufToWebSocketHandler();
            ServerBootstrap wsBootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, false)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            // HTTP 解码（WebSocket 握手需要 HTTP Upgrade）
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            p.addLast(new WebSocketServerProtocolHandler(
                                    "/ws", null, true, 65536));
                            // 0xACAC 二进制帧解析（从 BinaryWebSocketFrame.content() 读取）
                            p.addLast(new WebSocketIMDecoder());
                            // 出站：IMCommand → ByteBuf(0xACAC) → BinaryWebSocketFrame
                            // 注意 ByteBufToWebSocketHandler 必须在 IMEncoder 之前，
                            // 因为出站从tail→head: IMEncoder→ByteBufToWSHandler→WSProtocolHandler
                            p.addLast(byteBufToWebSocketHandler);
                            p.addLast(new IMEncoder());
                            // 无 IdleStateHandler —— WebSocket 原生 Ping/Pong 保活
                            p.addLast(connectionEventHandler);
                            p.addLast(routerHandler);
                        }
                    });

            ChannelFuture wsFuture = wsBootstrap.bind(config.getWsPort()).sync();
            wsChannel = wsFuture.channel();
            log.info("WebSocket server started: port={}, path=/ws", config.getWsPort());
        }
    }

    @Override
    public void shutdown() throws Exception {
        log.info("Shutting down...");
        deliveryConsumer.shutdown();
        persistenceConsumer.shutdown();
        messageQueue.shutdown();
        nodeDiscovery.unregister();
        nodeDiscovery.shutdown();
        if (scanScheduler != null) scanScheduler.shutdown();
        routerHandler.shutdown();
        connectionEventHandler.shutdown();
        pendingAcknowledgementManager.shutdown();
        sessionManager.clear();
        if (redisConfig != null) redisConfig.close();
        if (serverChannel != null) serverChannel.close().sync();
        if (wsChannel != null) wsChannel.close().sync();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        log.info("Shutdown complete");
    }

    private NodeInformation buildNodeInformation() {
        String host = "127.0.0.1";
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
        }
        Map<String, String> attrs = new java.util.HashMap<>();
        if (config.isWsEnabled()) {
            attrs.put("webSocketPort", String.valueOf(config.getWsPort()));
        }
        return new NodeInformation(config.getNodeId(), host, config.getPort(), attrs);
    }

    public ISessionManager getSessionManager() {
        return sessionManager;
    }

    public IMessageQueue getMessageQueue() {
        return messageQueue;
    }

    public INodeDiscovery getNodeDiscovery() {
        return nodeDiscovery;
    }

    public IRouteTable getRouteTable() {
        return routeTable;
    }

    // ========== main ==========

    private static boolean dbEnabled(ServerConfiguration config) {
        String dbProp = System.getProperty("db.enabled");
        if ("true".equalsIgnoreCase(dbProp)) return true;
        String jdbcUrl = System.getProperty("db.jdbcUrl");
        return jdbcUrl != null && !jdbcUrl.isEmpty();
    }

    public static void main(String[] args) throws Exception {
        ServerConfiguration config = new ServerConfiguration();
        if (args.length > 0) {
            config.setPort(Integer.parseInt(args[0]));
        }
        if (args.length > 1) {
            config.setNodeId(args[1]);
        }

        // 数据库初始化（如果启用了 DB）
        if (dbEnabled(config)) {
            DatabaseConfiguration dbConfig = DatabaseConfiguration.develop();
            String urlProp = System.getProperty("db.jdbcUrl");
            if (urlProp != null && !urlProp.isEmpty()) {
                dbConfig = new DatabaseConfiguration.Builder()
                        .jdbcUrl(urlProp)
                        .username(System.getProperty("db.username", "root"))
                        .password(System.getProperty("db.password", "password"))
                        .build();
            }
            try {
                MyBatisPlusFactory.init(dbConfig);
                log.info("Database initialized: {}", dbConfig.getJdbcUrl());
            } catch (Exception e) {
                log.error("Failed to initialize database, falling back to in-memory storage", e);
            }
        } else {
            log.info("Database disabled (use -Ddb.enabled=true or -Ddb.jdbcUrl to enable)");
        }

        IMServer server = new IMServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.shutdown(); } catch (Exception e) { log.error("Shutdown error", e); }
        }));

        server.start();
        log.info("Server ready. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
