package com.im.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 ConcurrentHashMap 的本地缓存实现，支持 TTL。
 *
 * <p>特性：
 * <ul>
 *   <li>单节点内存缓存，适合本地开发和小规模部署</li>
 *   <li>每个 key 独立 TTL，过期自动失效（惰性删除 + 定时清理）</li>
 *   <li>定时清理线程每 60s 扫描一次过期条目</li>
 * </ul>
 *
 * <p>线程安全：所有操作通过 ConcurrentHashMap 保证。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class ConcurrentHashCache<K, V> implements Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentHashCache.class);

    /** 默认 TTL（秒），不设置时使用此值 */
    private static final long DEFAULT_TTL_SECONDS = 300;

    /** 清理间隔（秒） */
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    private final ConcurrentMap<K, CacheEntry<V>> store;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicInteger cleanupCount = new AtomicInteger(0);

    public ConcurrentHashCache() {
        this.store = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpired,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    // ========== Cache<K,V> 接口实现 ==========

    @Override
    public Optional<V> get(K key) {
        CacheEntry<V> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value);
    }

    @Override
    public Map<K, Optional<V>> getAllPresent(Set<?> keys) {
        Map<K, Optional<V>> result = new LinkedHashMap<>();
        for (Object key : keys) {
            @SuppressWarnings("unchecked")
            K k = (K) key;
            result.put(k, get(k));
        }
        return result;
    }

    @Override
    public void put(K key, V value) {
        put(key, value, DEFAULT_TTL_SECONDS);
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        long expireAt = System.currentTimeMillis() + DEFAULT_TTL_SECONDS * 1000;
        CacheEntry<V> newEntry = new CacheEntry<>(value, expireAt);
        CacheEntry<V> old = store.putIfAbsent(key, newEntry);
        return old == null;
    }

    @Override
    public boolean invalidate(K key) {
        return store.remove(key) != null;
    }

    @Override
    public void invalidateAll(Set<?> keys) {
        for (Object key : keys) {
            store.remove(key);
        }
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public long estimatedSize() {
        return store.size();
    }

    @Override
    public CacheStats stats() {
        return CacheStats.EMPTY;
    }

    // ========== 内部带 TTL 写入 ==========

    private void put(K key, V value, long ttlSeconds) {
        long expireAt = ttlSeconds > 0
                ? System.currentTimeMillis() + ttlSeconds * 1000
                : Long.MAX_VALUE;
        store.put(key, new CacheEntry<>(value, expireAt));
    }

    /** 惰性删除 + 定时清理过期条目 */
    private void cleanupExpired() {
        try {
            int removed = 0;
            for (var it = store.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getValue().isExpired()) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                int total = cleanupCount.addAndGet(removed);
                log.debug("Cache cleanup: removed {} expired entries (total={})", removed, total);
            }
        } catch (Exception e) {
            log.warn("Cache cleanup error", e);
        }
    }

    /**
     * 关闭缓存，停止清理线程。缓存数据仍然可用（只是不再清理过期项）。
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
    }

    // ========== 内部条目 ==========

    private record CacheEntry<V>(V value, long expireAt) {
        boolean isExpired() {
            return expireAt < System.currentTimeMillis();
        }
    }
}
