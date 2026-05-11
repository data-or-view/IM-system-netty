package com.im.api.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 通用缓存接口。
 *
 * <p>缓存作为 L1 加速层，位于 Manager 之上，Manager 之下是持久化存储。
 * 读流程：先查缓存 → miss → 回调 Supplier 加载数据 → 填充缓存 → 返回
 * 写流程：写持久化 → 删除缓存
 *
 * <p>所有实现必须遵循「安全优先」原则：
 * 缓存崩溃 → 降级到数据源，不影响程序正常运行。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface ICache<K, V> {

    /**
     * 从缓存取值，若不存在返回 empty。
     * 缓存内部会校验 TTL，过期视为不存在。
     */
    Optional<V> get(K key);

    /**
     * 从缓存取值，miss 时通过 loader 加载并填充缓存。
     * loader 的返回值会写入缓存（含默认 TTL），null 不会被缓存。
     */
    default V getOrLoad(K key, Supplier<V> loader) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        V loaded = loader.get();
        if (loaded != null) {
            set(key, loaded);
        }
        return loaded;
    }

    /**
     * 从缓存取值，miss 时通过 loader 加载并填充缓存（自定义 TTL）。
     */
    default V getOrLoad(K key, Supplier<V> loader, long ttlSeconds) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        V loaded = loader.get();
        if (loaded != null) {
            set(key, loaded, ttlSeconds);
        }
        return loaded;
    }

    /**
     * 写入缓存（默认 TTL）。
     */
    void set(K key, V value);

    /**
     * 写入缓存（自定义 TTL 秒数，<=0 表示永不过期）。
     */
    void set(K key, V value, long ttlSeconds);

    /**
     * 删除单个缓存项。
     */
    void delete(K key);

    /**
     * 清空全部缓存。
     */
    void clear();

    /**
     * 当前缓存条目数（近似值，仅用于监控/调试）。
     */
    int size();
}
