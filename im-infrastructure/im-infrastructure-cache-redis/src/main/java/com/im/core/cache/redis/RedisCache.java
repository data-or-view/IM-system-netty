package com.im.core.cache.redis;

import com.im.common.lifecycle.Lifecycle;
import com.im.core.cache.Cache;
import com.im.core.cache.CacheStats;
import com.im.core.serialization.Serializer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 基于 Redis（Lettuce）的缓存实现。
 *
 * <p>序列化方式：Jackson JSON。所有值先序列化为 JSON 字符串再存入 Redis。
 * 缓存 key 通过 keyPrefix + keyMapper.apply(key) 映射为 Redis 的 string key。
 *
 * <p>本地维护已写入的 key 集合，用于 {@link #clear()} 和 {@link #estimatedSize()}。
 * 该集合仅保证最终一致，过期/淘汰的 key 可能残留，但不影响正确性。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class RedisCache<K, V> implements Cache<K, V>, Lifecycle, AutoCloseable {

    private final RedisClient client;
    private final Function<K, String> keyMapper;
    private final Serializer<V, String> serializer;
    private final Class<V> valueType;
    private final String keyPrefix;
    private final Duration defaultTtl;

    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> sync;

    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    public RedisCache(RedisClient client,
                      Function<K, String> keyMapper,
                      Serializer<V, String> serializer,
                      Class<V> valueType,
                      String keyPrefix,
                      Duration defaultTtl) {
        this.client = client;
        this.keyMapper = keyMapper;
        this.serializer = serializer;
        this.valueType = valueType;
        this.keyPrefix = keyPrefix;
        this.defaultTtl = defaultTtl;
    }

    // ========== 生命周期 ==========

    @Override
    public void start() {
        this.connection = client.connect();
        this.sync = connection.sync();
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ========== 读 ==========

    @Override
    public Optional<V> get(K key) {
        String raw = sync.get(redisKey(key));
        if (raw == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        hitCount.incrementAndGet();
        return Optional.of(deserialize(raw));
    }

    @Override
    public Map<K, Optional<V>> getAllPresent(Set<?> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        String[] redisKeys = keys.stream()
                .map(k -> redisKeyFromWildcard(k))
                .toArray(String[]::new);

        var values = sync.mget(redisKeys);

        Map<K, Optional<V>> result = new LinkedHashMap<>();
        int i = 0;
        for (Object key : keys) {
            String raw = values.get(i++).getValueOrElse(null);
            if (raw != null) {
                hitCount.incrementAndGet();
                @SuppressWarnings("unchecked")
                K k = (K) key;
                result.put(k, Optional.of(deserialize(raw)));
            } else {
                missCount.incrementAndGet();
                @SuppressWarnings("unchecked")
                K k = (K) key;
                result.put(k, Optional.empty());
            }
        }
        return result;
    }

    // ========== 写 ==========

    @Override
    public void put(K key, V value) {
        String rk = redisKey(key);
        String json = serialize(value);
        if (defaultTtl != null && !defaultTtl.isNegative()) {
            sync.setex(rk, defaultTtl.toSeconds(), json);
        } else {
            sync.set(rk, json);
        }
        keys.add(rk);
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        String rk = redisKey(key);
        String json = serialize(value);
        SetArgs args = SetArgs.Builder.nx();
        if (defaultTtl != null && !defaultTtl.isNegative()) {
            args = args.ex(defaultTtl.toSeconds());
        }
        String result = sync.set(rk, json, args);
        // SET NX 返回 "OK" 表示成功写入，null 表示 key 已存在
        boolean success = "OK".equals(result);
        if (success) {
            keys.add(rk);
        }
        return success;
    }

    // ========== 删 ==========

    @Override
    public boolean invalidate(K key) {
        String rk = redisKey(key);
        long removed = sync.del(rk);
        keys.remove(rk);
        return removed > 0;
    }

    @Override
    public void invalidateAll(Set<?> keys) {
        if (keys.isEmpty()) {
            return;
        }
        String[] redisKeys = keys.stream()
                .map(k -> redisKeyFromWildcard(k))
                .toArray(String[]::new);
        sync.del(redisKeys);
        for (String rk : redisKeys) {
            this.keys.remove(rk);
        }
    }

    @Override
    public void clear() {
        if (keys.isEmpty()) {
            return;
        }
        String[] keyArray = keys.toArray(new String[0]);
        sync.del(keyArray);
        keys.clear();
    }

    // ========== 运维 ==========

    @Override
    public long estimatedSize() {
        return keys.size();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(
                hitCount.get(),
                missCount.get(),
                evictionCount.get(),
                0,
                0
        );
    }

    // ========== 内部 ==========

    private String redisKey(K key) {
        return keyPrefix + keyMapper.apply(key);
    }

    private String redisKeyFromWildcard(Object key) {
        @SuppressWarnings("unchecked")
        K k = (K) key;
        return redisKey(k);
    }

    private String serialize(V value) {
        return serializer.serialize(value);
    }

    private V deserialize(String raw) {
        return serializer.deserialize(raw, valueType);
    }
}
