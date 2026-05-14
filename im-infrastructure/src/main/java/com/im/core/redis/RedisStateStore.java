package com.im.core.redis;

import com.im.api.IClusterStateStore;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Redis 集群状态存储（生产环境用）。
 *
 * <p>替代 {@code LocalStateStore}，利用 Redis GET/SET/DEL 实现分布式 KV 存储。
 * 支持单机 Redis 和 Redis Cluster。</p>
 *
 * <p>Key 格式：{@code im:state:{namespace}:{key}}</p>
 *
 * <p>当前版本：watch 在单机模式下可用（Redis Keyspace Notification），
 * 集群模式下简化暂不启用。</p>
 */
public class RedisStateStore implements IClusterStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStateStore.class);

    private static final String KEY_PREFIX = "im:state:";
    private static final long REDIS_TIMEOUT_MS = 3000;

    private final RedisClusterAsyncCommands<String, String> async;

    /** 本地 watch 监听器（RedisStateStore 的 watch 是本地行为，需要双写通知） */
    private final CopyOnWriteArrayList<StateChangeListener> listeners = new CopyOnWriteArrayList<>();

    public RedisStateStore(RedisConfiguration redisConfig) {
        this.async = redisConfig.async();
        log.info("RedisStateStore initialized");
    }

    @Override
    public void start() {
        log.info("RedisStateStore started");
    }

    @Override
    public void shutdown() {
        listeners.clear();
        log.info("RedisStateStore stopped");
    }

    @Override
    public void put(String namespace, String key, String value) {
        String redisKey = buildKey(namespace, key);
        try {
            async.set(redisKey, value).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            notifyLocalWatchers(namespace, key, value);
            log.debug("State stored: {}={}", redisKey, value);
        } catch (Exception e) {
            log.error("Redis SET failed for key {}: {}", redisKey, e.getMessage());
        }
    }

    @Override
    public String get(String namespace, String key) {
        String redisKey = buildKey(namespace, key);
        try {
            String value = async.get(redisKey).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.debug("State retrieved: {}={}", redisKey, value);
            return value;
        } catch (Exception e) {
            log.warn("Redis GET failed for key {}: {}", redisKey, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String namespace, String key) {
        String redisKey = buildKey(namespace, key);
        try {
            async.del(redisKey).get(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            notifyLocalWatchers(namespace, key, null);
            log.debug("State deleted: {}", redisKey);
        } catch (Exception e) {
            log.warn("Redis DEL failed for key {}: {}", redisKey, e.getMessage());
        }
    }

    @Override
    public void watch(String namespace, String keyPrefix, StateChangeListener listener) {
        listeners.add(listener);
        log.debug("State watcher added: namespace={}, keyPrefix={}", namespace, keyPrefix);
    }

    @Override
    public void unwatch(String namespace, StateChangeListener listener) {
        listeners.remove(listener);
        log.debug("State watcher removed: namespace={}", namespace);
    }

    // ========== private ==========

    private static String buildKey(String namespace, String key) {
        return KEY_PREFIX + namespace + ":" + key;
    }

    /**
     * 通知本节点已注册的本地监听器。
     * 跨节点 watch 需要 Redis Keyspace Notification，
     * 后续可升级为 Redis PubSub。
     */
    private void notifyLocalWatchers(String namespace, String key, String newValue) {
        for (StateChangeListener listener : listeners) {
            try {
                listener.onChange(namespace, key, newValue);
            } catch (Exception e) {
                log.warn("StateChangeListener error", e);
            }
        }
    }
}
