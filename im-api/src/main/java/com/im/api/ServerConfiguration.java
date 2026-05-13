package com.im.api;

import java.util.List;
import java.util.Properties;

/**
 * 服务端配置 POJO。
 * 纯数据类，不含业务逻辑。
 *
 * <p>可通过 {@link #from(Properties)} 或 {@link #from(PropertiesSource)} 加载。</p>
 */
public class ServerConfiguration {

    /** 默认绑定端口 */
    public static final int DEFAULT_PORT = 8080;

    /** 默认 Boss 线程数 */
    public static final int DEFAULT_BOSS_THREADS = 1;

    /** 默认 Worker 线程数（IO 线程） */
    public static final int DEFAULT_WORKER_THREADS = 0; // 0 = Netty 自动选择

    /** 默认业务线程数 */
    public static final int DEFAULT_BUSINESS_THREADS = 8;

    /** 默认本节点 ID（hostname:port） */
    public static final String DEFAULT_NODE_ID = "node-1";

    /** 默认 token 签名密钥 */
    public static final String DEFAULT_TOKEN_SECRET = "im-system-dev-secret-change-in-production";

    /** 默认空闲超时秒数 */
    public static final int DEFAULT_IDLE_TIME_SECONDS = 180;

    /** 默认心跳超时秒数 */
    public static final int DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 120;

    /** 默认 WebSocket 端口 */
    public static final int DEFAULT_WEB_SOCKET_PORT = 8081;

    /** 默认是否启用 WebSocket */
    public static final boolean DEFAULT_WEB_SOCKET_ENABLED = true;

    /** 最大帧长度（4MB，防止恶意大包） */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 4 * 1024 * 1024;

    /** Socket 接收缓冲区 */
    public static final int DEFAULT_SOCKET_RECEIVE_BUFFER = 65535;

    /** Socket 发送缓冲区 */
    public static final int DEFAULT_SOCKET_SEND_BUFFER = 65535;

    private int port = DEFAULT_PORT;
    private int bossThreads = DEFAULT_BOSS_THREADS;
    private int workerThreads = DEFAULT_WORKER_THREADS;
    private int businessThreads = DEFAULT_BUSINESS_THREADS;
    private int idleTimeSeconds = DEFAULT_IDLE_TIME_SECONDS;
    private int heartbeatTimeoutSeconds = DEFAULT_HEARTBEAT_TIMEOUT_SECONDS;
    private int maxFrameLength = DEFAULT_MAX_FRAME_LENGTH;
    private int socketReceiveBufferSize = DEFAULT_SOCKET_RECEIVE_BUFFER;
    private int socketSendBufferSize = DEFAULT_SOCKET_SEND_BUFFER;
    private boolean useEpoll = true;
    private String nodeId = DEFAULT_NODE_ID;
    private String tokenSecret = DEFAULT_TOKEN_SECRET;

    // WebSocket
    private int webSocketPort = DEFAULT_WEB_SOCKET_PORT;
    private boolean webSocketEnabled = DEFAULT_WEB_SOCKET_ENABLED;

    // Webhook
    private String webhookUrl = "";

    // Redis（在线状态）
    private String redisHost = "";
    private int redisPort = 6379;
    private String redisPassword = "";
    private int redisDatabase = 0;

    // ========== Factory Methods ==========

    /**
     * 从 {@link Properties} 加载配置。
     *
     * <p>键命名空间为 {@code im.server.xxx}、{@code im.redis.xxx} 等。</p>
     *
     * <pre>
     * Properties props = new Properties();
     * props.setProperty("im.server.port", "8081");
     * props.setProperty("im.token.secret", "my-secret");
     * ServerConfiguration config = ServerConfiguration.from(props);
     * </pre>
     */
    public static ServerConfiguration from(Properties props) {
        ServerConfiguration cfg = new ServerConfiguration();
        cfg.port = get(props, "im.server.port", DEFAULT_PORT);
        cfg.bossThreads = get(props, "im.server.boss-threads", DEFAULT_BOSS_THREADS);
        cfg.workerThreads = get(props, "im.server.worker-threads", DEFAULT_WORKER_THREADS);
        cfg.businessThreads = get(props, "im.server.business-threads", DEFAULT_BUSINESS_THREADS);
        cfg.idleTimeSeconds = get(props, "im.server.idle-timeout", DEFAULT_IDLE_TIME_SECONDS);
        cfg.heartbeatTimeoutSeconds = get(props, "im.server.heartbeat-timeout", DEFAULT_HEARTBEAT_TIMEOUT_SECONDS);
        cfg.maxFrameLength = get(props, "im.server.max-frame-length", DEFAULT_MAX_FRAME_LENGTH);
        cfg.socketReceiveBufferSize = get(props, "im.server.socket-rcv-buf", DEFAULT_SOCKET_RECEIVE_BUFFER);
        cfg.socketSendBufferSize = get(props, "im.server.socket-snd-buf", DEFAULT_SOCKET_SEND_BUFFER);
        cfg.useEpoll = get(props, "im.server.use-epoll", true);
        cfg.nodeId = get(props, "im.node.id", DEFAULT_NODE_ID);
        cfg.tokenSecret = get(props, "im.token.secret", DEFAULT_TOKEN_SECRET);
        cfg.webSocketPort = get(props, "im.ws.port", DEFAULT_WEB_SOCKET_PORT);
        cfg.webSocketEnabled = get(props, "im.ws.enabled", DEFAULT_WEB_SOCKET_ENABLED);
        cfg.webhookUrl = get(props, "im.webhook.url", "");
        cfg.redisHost = get(props, "im.redis.host", "");
        cfg.redisPort = get(props, "im.redis.port", 6379);
        cfg.redisPassword = get(props, "im.redis.password", "");
        cfg.redisDatabase = get(props, "im.redis.database", 0);
        return cfg;
    }

    private static String get(Properties props, String key, String def) {
        String val = props.getProperty(key);
        return val != null ? val : def;
    }

    private static int get(Properties props, String key, int def) {
        String val = props.getProperty(key);
        if (val == null) return def;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean get(Properties props, String key, boolean def) {
        String val = props.getProperty(key);
        if (val == null) return def;
        return "true".equalsIgnoreCase(val.trim());
    }

    // ========== Getters / Setters ==========

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getBossThreads() { return bossThreads; }
    public void setBossThreads(int bossThreads) { this.bossThreads = bossThreads; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    public int getBusinessThreads() { return businessThreads; }
    public void setBusinessThreads(int businessThreads) { this.businessThreads = businessThreads; }

    public int getIdleTimeSeconds() { return idleTimeSeconds; }
    public void setIdleTimeSeconds(int idleTimeSeconds) { this.idleTimeSeconds = idleTimeSeconds; }

    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }

    public int getMaxFrameLength() { return maxFrameLength; }
    public void setMaxFrameLength(int maxFrameLength) { this.maxFrameLength = maxFrameLength; }

    public int getSocketRcvBufSize() { return socketReceiveBufferSize; }
    public void setSocketRcvBufSize(int socketReceiveBufferSize) { this.socketReceiveBufferSize = socketReceiveBufferSize; }

    public int getSocketSndBufSize() { return socketSendBufferSize; }
    public void setSocketSndBufSize(int socketSendBufferSize) { this.socketSendBufferSize = socketSendBufferSize; }

    public boolean isUseEpoll() { return useEpoll; }
    public void setUseEpoll(boolean useEpoll) { this.useEpoll = useEpoll; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }

    // WebSocket

    public int getWsPort() { return webSocketPort; }
    public void setWsPort(int webSocketPort) { this.webSocketPort = webSocketPort; }

    public boolean isWsEnabled() { return webSocketEnabled; }
    public void setWsEnabled(boolean webSocketEnabled) { this.webSocketEnabled = webSocketEnabled; }

    // Webhook

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    // Redis

    public boolean isRedisEnabled() { return redisHost != null && !redisHost.isEmpty(); }

    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }

    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }

    public String getRedisPassword() { return redisPassword; }
    public void setRedisPassword(String redisPassword) { this.redisPassword = redisPassword; }

    public int getRedisDatabase() { return redisDatabase; }
    public void setRedisDatabase(int redisDatabase) { this.redisDatabase = redisDatabase; }
}
