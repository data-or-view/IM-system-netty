package com.im.core.redis;

import com.im.common.lifecycle.Lifecycle;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Redis 连接配置（Lettuce）。
 *
 * <p>支持单机模式（RedisClient）和集群模式（RedisClusterClient）。
 * 统一返回 {@link RedisClusterAsyncCommands}（单机 {@code RedisAsyncCommands} / 集群 {@code RedisAdvancedClusterAsyncCommands} 都继承此接口）。</p>
 */
public class RedisConfiguration implements Lifecycle, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConfiguration.class);
    private static final int DEFAULT_REDIS_PORT = 6379;

    /** 统一的命令接口（单机 {@code RedisAsyncCommands} / 集群 {@code RedisAdvancedClusterAsyncCommands} 都赋值给此父接口） */
    private final RedisClusterAsyncCommands<String, String> async;

    /** 资源（非 null 的那个在 close 中使用） */
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> clusterConnection;
    private final boolean clusterMode;

    /** 单机构造（{@code RedisAsyncCommands} 是 {@code RedisClusterAsyncCommands} 的子接口，赋值兼容） */
    RedisConfiguration(RedisClient client) {
        this.client = client;
        this.connection = client.connect();
        this.async = connection.async();
        this.clusterClient = null;
        this.clusterConnection = null;
        this.clusterMode = false;
        log.info("Redis (standalone) connected");
    }

    /** 集群构造（{@code RedisAdvancedClusterAsyncCommands} 是 {@code RedisClusterAsyncCommands} 的子接口） */
    RedisConfiguration(RedisClusterClient clusterClient) {
        this.client = null;
        this.connection = null;
        this.clusterClient = clusterClient;
        this.clusterConnection = clusterClient.connect();
        this.async = clusterConnection.async();
        this.clusterMode = true;
        log.info("Redis (cluster) connected: {} partitions",
                clusterClient.getPartitions().size());
    }

    /** 获取统一的异步命令接口（单机/集群均兼容，返回 {@link RedisClusterAsyncCommands}） */
    public RedisClusterAsyncCommands<String, String> async() {
        return async;
    }

    /** 是否为集群模式 */
    public boolean isClusterMode() {
        return clusterMode;
    }

    /**
     * 创建专用的同步命令封装（用于阻塞操作，如 XREADGROUP BLOCK）。
     * <p>必须与 {@code async()} 返回的命令接口分开使用独立的连接。</p>
     * <p>使用 try-with-resources 自动释放底层连接：
     * {@code try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) { ... }}</p>
     */
    public CloseableRedisCommands createSyncCommands() {
        if (clusterClient != null) {
            StatefulRedisClusterConnection<String, String> conn = clusterClient.connect();
            return new CloseableRedisCommands(conn, conn.sync());
        }
        StatefulRedisConnection<String, String> conn = client.connect();
        return new CloseableRedisCommands(conn, conn.sync());
    }

    /**
     * 创建专用的 pub/sub 连接。
     * <p>必须与 {@code async()} 返回的命令接口分开使用独立的连接，
     * 因为 Lettuce 的 pub/sub 是连接级阻塞的。</p>
     */
    public StatefulRedisPubSubConnection<String, String> createPubSubConnection() {
        if (clusterClient != null) {
            return clusterClient.connectPubSub();
        }
        return client.connectPubSub();
    }

    // ========== 生命周期 ==========

    @Override
    public void start() {
        // 连接已在 build() 中创建，无额外初始化
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public void close() {
        if (clusterConnection != null) clusterConnection.close();
        if (connection != null) connection.close();
        if (clusterClient != null) clusterClient.shutdown();
        if (client != null) client.shutdown();
        log.info("Redis {}connection closed", clusterMode ? "cluster " : "");
    }

    // ========== Builder ==========

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String host = "localhost";
        private int port = DEFAULT_REDIS_PORT;
        private List<String> clusterNodes = Collections.emptyList();
        private String username;
        private String password;
        private int database = 0;
        private Duration timeout = Duration.ofSeconds(3);

        Builder() {}

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder clusterNodes(String... nodes) {
            this.clusterNodes = new ArrayList<>();
            Collections.addAll(this.clusterNodes, nodes);
            return this;
        }
        public Builder clusterNodes(List<String> nodes) {
            this.clusterNodes = new ArrayList<>(nodes);
            return this;
        }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder database(int database) { this.database = database; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public RedisConfiguration build() {
            if (!clusterNodes.isEmpty()) {
                // 集群模式
                List<RedisURI> uris = new ArrayList<>();
                for (String node : clusterNodes) {
                    String[] p = node.split(":");
                    RedisURI.Builder b = RedisURI.builder()
                            .withHost(p[0])
                            .withPort(p.length > 1 ? Integer.parseInt(p[1]) : DEFAULT_REDIS_PORT)
                            .withTimeout(timeout);
                    applyAuthentication(b);
                    uris.add(b.build());
                }
                RedisClusterClient cc = RedisClusterClient.create(uris);
                cc.setOptions(ClusterClientOptions.builder()
                        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                                .enablePeriodicRefresh(Duration.ofSeconds(30))
                                .enableAllAdaptiveRefreshTriggers()
                                .build())
                        .build());
                return new RedisConfiguration(cc);
            } else {
                // 单机模式
                Objects.requireNonNull(host, "host must not be null");
                RedisURI.Builder b = RedisURI.builder()
                        .withHost(host).withPort(port)
                        .withDatabase(database).withTimeout(timeout);
                applyAuthentication(b);
                return new RedisConfiguration(RedisClient.create(b.build()));
            }
        }

        private void applyAuthentication(RedisURI.Builder builder) {
            if (password == null || password.isEmpty()) {
                return;
            }
            if (username != null && !username.isBlank()) {
                builder.withAuthentication(username, password.toCharArray());
            } else {
                builder.withPassword(password.toCharArray());
            }
        }
    }
}
