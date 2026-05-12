package com.im.core.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
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
 * 支持单机模式（RedisClient）和集群模式（RedisClusterClient）。
 * 统一返回 {@link RedisAdvancedClusterAsyncCommands}，RedisRouteTable 无需关心类型。
 */
public class RedisConfiguration implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConfiguration.class);

    /** 统一的集群命令接口（单机模式下用 unchecked cast 兼容） */
    private final RedisAdvancedClusterAsyncCommands<String, String> async;

    /** 资源（非 null 的那个在 close 中使用） */
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> clusterConnection;
    private final boolean clusterMode;

    /** 单机构造 */
    @SuppressWarnings("unchecked")
    RedisConfiguration(RedisClient client) {
        this.client = client;
        this.connection = client.connect();
        // RedisAsyncCommands 与 RedisAdvancedClusterAsyncCommands
        // 对 ZADD/ZRANGEBYSCORE/EVALSHA 等方法的签名一致，
        // 通过 Object 做 unchecked cast，运行时安全。
        this.async = (RedisAdvancedClusterAsyncCommands<String, String>)
                (Object) connection.async();
        this.clusterClient = null;
        this.clusterConnection = null;
        this.clusterMode = false;
        log.info("Redis (standalone) connected");
    }

    /** 集群构造 */
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

    /** 获取统一的异步命令接口（单机/集群均兼容） */
    public RedisAdvancedClusterAsyncCommands<String, String> async() {
        return async;
    }

    /** 是否为集群模式 */
    public boolean isClusterMode() {
        return clusterMode;
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
        private int port = 6379;
        private List<String> clusterNodes = Collections.emptyList();
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
                            .withPort(p.length > 1 ? Integer.parseInt(p[1]) : 6379)
                            .withTimeout(timeout);
                    if (password != null && !password.isEmpty())
                        b.withPassword(password.toCharArray());
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
                if (password != null && !password.isEmpty())
                    b.withPassword(password.toCharArray());
                return new RedisConfiguration(RedisClient.create(b.build()));
            }
        }
    }
}
