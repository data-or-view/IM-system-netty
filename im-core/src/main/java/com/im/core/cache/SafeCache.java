package com.im.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 安全缓存装饰器 —— 保证缓存异常绝不传播到业务层。
 *
 * <p>装饰模式包装任意 {@link Cache} 实现，每个方法都包裹 try-catch，
 * 任何异常都降级为「缓存未命中」行为，业务层完全无感知。
 *
 * <p>适用场景：
 * <ul>
 *   <li>Redis 缓存不可用时，自动降级到数据源</li>
 *   <li>本地缓存 ConcurrentHashMap 出现罕见 OOM/并发异常时静默忽略</li>
 *   <li>缓存重构/迁移期，即使缓存出错也不影响主流程</li>
 * </ul>
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
