package com.im.core.cache;

import com.im.api.cache.ICache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 安全缓存装饰器 —— 保证缓存异常绝不传播到业务层。
 *
 * <p>装饰模式包装任意 {@link ICache} 实现，每个方法都包裹 try-catch，
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
public class SafeCache<K, V> implements ICache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(SafeCache.class);

    private final ICache<K, V> delegate;
    private final String name;

    public SafeCache(ICache<K, V> delegate) {
        this(delegate, delegate.getClass().getSimpleName());
    }

    public SafeCache(ICache<K, V> delegate, String name) {
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
    public V getOrLoad(K key, Supplier<V> loader) {
        try {
            Optional<V> cached = delegate.get(key);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            log.warn("[SafeCache:{}] getOrLoad({}) cache read failed, fall through: {}",
                    name, key, e.toString());
        }
        // 缓存 miss 或异常 → 直接 load
        V loaded = loader.get();
        if (loaded != null) {
            try {
                delegate.set(key, loaded);
            } catch (Exception e) {
                log.warn("[SafeCache:{}] getOrLoad({}) cache write failed, ignore: {}",
                        name, key, e.toString());
            }
        }
        return loaded;
    }

    @Override
    public V getOrLoad(K key, Supplier<V> loader, long ttlSeconds) {
        try {
            Optional<V> cached = delegate.get(key);
            if (cached.isPresent()) {
                return cached.get();
            }
        } catch (Exception e) {
            log.warn("[SafeCache:{}] getOrLoad({}) cache read failed, fall through: {}",
                    name, key, e.toString());
        }
        V loaded = loader.get();
        if (loaded != null) {
            try {
                delegate.set(key, loaded, ttlSeconds);
            } catch (Exception e) {
                log.warn("[SafeCache:{}] getOrLoad({}) cache write failed, ignore: {}",
                        name, key, e.toString());
            }
        }
        return loaded;
    }

    @Override
    public void set(K key, V value) {
        try {
            delegate.set(key, value);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] set({}) failed, ignore: {}", name, key, e.toString());
        }
    }

    @Override
    public void set(K key, V value, long ttlSeconds) {
        try {
            delegate.set(key, value, ttlSeconds);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] set({}) failed, ignore: {}", name, key, e.toString());
        }
    }

    @Override
    public void delete(K key) {
        try {
            delegate.delete(key);
        } catch (Exception e) {
            log.warn("[SafeCache:{}] delete({}) failed, ignore: {}", name, key, e.toString());
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
    public int size() {
        try {
            return delegate.size();
        } catch (Exception e) {
            log.warn("[SafeCache:{}] size() failed, return 0: {}", name, e.toString());
            return 0;
        }
    }
}
