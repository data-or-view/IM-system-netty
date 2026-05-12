package com.im.api;

/**
 * 服务端配置 POJO。
 * 纯数据类，不含业务逻辑。
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
