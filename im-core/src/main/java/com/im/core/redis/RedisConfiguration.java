package com.im.core.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis 连接配置（Lettuce）。
 *
 * 使用方式：
 * <pre>
 * RedisConfiguration config = RedisConfiguration.builder()
 *         .host("localhost")
 *         .port(6379)
 *         .build();
 *
 * RedisRouteTable routeTable = new RedisRouteTable(config);
 * </pre>
 *
 * 线程安全：Lettuce 的连接是线程安全的，一个 connection 可多线程共享。
 * RedisAsyncCommands 返回 CompletableFuture，天然适配 Netty 异步模型。
 */
public class RedisConfiguration implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConfiguration.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;

    RedisConfiguration(RedisClient client) {
        this.client = client;
        this.connection = client.connect(new StringCodec());
        this.async = connection.async();
        log.info("Redis connected: {}", client.getResources());
    }

    /**
     * 获取异步命令接口。
     * 线程安全，所有 Redis 操作通过此接口执行。
     */
    public RedisAsyncCommands<String, String> async() {
        return async;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        log.info("Redis connection closed");
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
        private Duration timeout = Duration.ofSeconds(3);

        Builder() {}

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public RedisConfiguration build() {
            Objects.requireNonNull(host, "host must not be null");

            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withDatabase(database)
                    .withTimeout(timeout);

            if (password != null && !password.isEmpty()) {
                uriBuilder.withPassword(password.toCharArray());
            }

            RedisClient client = RedisClient.create(uriBuilder.build());
            return new RedisConfiguration(client);
        }
    }
}
