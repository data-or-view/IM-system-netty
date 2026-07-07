package com.im.core.cache.redis;

import com.im.core.cache.Cache;
import com.im.core.cache.CacheStats;
import com.im.core.serialization.Serializer;
import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Redis JSON cache backed by a shared standalone or cluster Lettuce command interface.
 *
 * <p>This implementation is cluster-safe because callers pass the same
 * {@link RedisClusterAsyncCommands} abstraction used by the rest of the server Redis runtime.
 * It does not maintain local key state, so {@link #clear()} is intentionally unsupported for
 * production prefixed caches.</p>
 */
public class RedisJsonCache<K, V> implements Cache<K, V> {

    private static final long COMMAND_TIMEOUT_MS = 3_000;

    private final RedisClusterAsyncCommands<String, String> async;
    private final Function<K, String> keyMapper;
    private final Serializer<V, String> serializer;
    private final Class<V> valueType;
    private final String keyPrefix;
    private final long ttlSeconds;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();

    public RedisJsonCache(RedisClusterAsyncCommands<String, String> async,
                          Function<K, String> keyMapper,
                          Serializer<V, String> serializer,
                          Class<V> valueType,
                          String keyPrefix,
                          Duration ttl) {
        this.async = Objects.requireNonNull(async, "async");
        this.keyMapper = Objects.requireNonNull(keyMapper, "keyMapper");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttlSeconds = Math.max(1, Objects.requireNonNull(ttl, "ttl").toSeconds());
    }

    @Override
    public Optional<V> get(K key) {
        String raw = await(async.get(redisKey(key)));
        if (raw == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }
        hitCount.incrementAndGet();
        return Optional.of(serializer.deserialize(raw, valueType));
    }

    @Override
    public Map<K, Optional<V>> getAllPresent(Set<?> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        String[] redisKeys = keys.stream()
                .map(this::redisKeyFromWildcard)
                .toArray(String[]::new);
        var values = await(async.mget(redisKeys));
        Map<K, Optional<V>> result = new LinkedHashMap<>();
        int index = 0;
        for (Object key : keys) {
            @SuppressWarnings("unchecked")
            K typedKey = (K) key;
            KeyValue<String, String> value = values.get(index++);
            if (value.hasValue()) {
                hitCount.incrementAndGet();
                result.put(typedKey, Optional.of(serializer.deserialize(value.getValue(), valueType)));
            } else {
                missCount.incrementAndGet();
                result.put(typedKey, Optional.empty());
            }
        }
        return result;
    }

    @Override
    public void put(K key, V value) {
        await(async.setex(redisKey(key), ttlSeconds, serializer.serialize(value)));
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        String result = await(async.set(
                redisKey(key),
                serializer.serialize(value),
                SetArgs.Builder.nx().ex(ttlSeconds)));
        return "OK".equals(result);
    }

    @Override
    public boolean invalidate(K key) {
        Long removed = await(async.del(redisKey(key)));
        if (removed != null && removed > 0) {
            evictionCount.incrementAndGet();
            return true;
        }
        return false;
    }

    @Override
    public void invalidateAll(Set<?> keys) {
        if (keys.isEmpty()) {
            return;
        }
        String[] redisKeys = keys.stream()
                .map(this::redisKeyFromWildcard)
                .toArray(String[]::new);
        Long removed = await(async.del(redisKeys));
        if (removed != null && removed > 0) {
            evictionCount.addAndGet(removed);
        }
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("RedisJsonCache.clear is intentionally unsupported for prefixed production cache");
    }

    @Override
    public long estimatedSize() {
        return -1;
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hitCount.get(), missCount.get(), evictionCount.get(), 0, 0);
    }

    private String redisKey(K key) {
        return keyPrefix + keyMapper.apply(key);
    }

    private String redisKeyFromWildcard(Object key) {
        @SuppressWarnings("unchecked")
        K typedKey = (K) key;
        return redisKey(typedKey);
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Redis cache command failed", e);
        }
    }
}
