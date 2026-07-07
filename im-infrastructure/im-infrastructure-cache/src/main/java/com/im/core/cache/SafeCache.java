package com.im.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 安全缓存装饰器，保证缓存异常不传播到业务层。
 *
 * <p>适合包装 Redis 等外部缓存：缓存不可用时降级为未命中，主业务继续读取权威数据源。</p>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class SafeCache<K, V> implements Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(SafeCache.class);

    private final Cache<K, V> delegate;
    private final String name;

    public SafeCache(Cache<K, V> delegate) {
        this(delegate, delegate.getClass().getSimpleName());
    }

    public SafeCache(Cache<K, V> delegate, String name) {
        this.delegate = delegate;
        this.name = name;
    }

    @Override
    public Optional<V> get(K key) {
        try {
            return delegate.get(key);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] get({}) failed, fall through: {}", name, key, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Map<K, Optional<V>> getAllPresent(Set<?> keys) {
        try {
            return delegate.getAllPresent(keys);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] getAllPresent() failed, fall through: {}", name, e.toString());
            Map<K, Optional<V>> result = new LinkedHashMap<>();
            for (Object key : keys) {
                @SuppressWarnings("unchecked")
                K k = (K) key;
                result.put(k, Optional.empty());
            }
            return result;
        }
    }

    @Override
    public void put(K key, V value) {
        try {
            delegate.put(key, value);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] put({}) failed, ignore: {}", name, key, e.toString());
        }
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        try {
            return delegate.putIfAbsent(key, value);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] putIfAbsent({}) failed, fall through: {}", name, key, e.toString());
            return false;
        }
    }

    @Override
    public boolean invalidate(K key) {
        try {
            return delegate.invalidate(key);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] invalidate({}) failed, fall through: {}", name, key, e.toString());
            return false;
        }
    }

    @Override
    public void invalidateAll(Set<?> keys) {
        try {
            delegate.invalidateAll(keys);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] invalidateAll() failed, ignore: {}", name, e.toString());
        }
    }

    @Override
    public void clear() {
        try {
            delegate.clear();
        } catch (Exception e) {
            log.warn("[SafeCache:{}] clear() failed, ignore: {}", name, e.toString());
        }
    }

    @Override
    public long estimatedSize() {
        try {
            return delegate.estimatedSize();
        } catch (Exception e) {
            log.warn("[SafeCache:{}] estimatedSize() failed, return 0: {}", name, e.toString());
            return 0;
        }
    }

    @Override
    public CacheStats stats() {
        try {
            return delegate.stats();
        } catch (Exception e) {
            log.warn("[SafeCache:{}] stats() failed, return EMPTY: {}", name, e.toString());
            return CacheStats.EMPTY;
        }
    }
}
