package com.im.core.cache;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 通用缓存接口。
 *
 * <p>缓存是临时键值存储，不承担数据加载职责。本接口只定义读、写、删三个维度的原语。
 *
 * <p>设计决策：
 * <ul>
 *   <li>返回值用 {@link Optional}，强制调用方处理 miss 情况</li>
 *   <li>空值不允许写入（null key / null value），违反则抛出 {@link NullPointerException}</li>
 *   <li>TTL 由实现配置决定，不在接口方法参数中</li>
 *   <li>数据加载是调用方职责，通过 {@code cache.get(key).orElseGet(() -> {...})} 组合</li>
 * </ul>
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public interface Cache<K, V> {

    // ========== 读 ==========

    /**
     * 获取缓存值。
     *
     * @param key 查询键，不能为 null
     * @return 存在且未过期返回对应值，否则 {@link Optional#empty()}
     * @throws NullPointerException key 为 null
     */
    Optional<V> get(K key);

    /**
     * 批量获取缓存值。
     * <p>返回结果包含所有请求的 key，不存在的 key 对应 {@link Optional#empty()}。
     *
     * @param keys 查询键集合，不能为 null 且不能包含 null 元素
     * @return key 到 Optional 值的映射
     * @throws NullPointerException keys 为 null 或包含 null 元素
     */
    Map<K, Optional<V>> getAllPresent(Set<?> keys);

    // ========== 写 ==========

    /**
     * 写入缓存。
     *
     * @param key   键，不能为 null
     * @param value 值，不能为 null
     * @throws NullPointerException key 或 value 为 null
     */
    void put(K key, V value);

    /**
     * 仅当 key 不存在时写入。
     *
     * @param key   键，不能为 null
     * @param value 值，不能为 null
     * @return true 如果成功写入（即 key 之前不存在）
     * @throws NullPointerException key 或 value 为 null
     */
    boolean putIfAbsent(K key, V value);

    // ========== 删 ==========

    /**
     * 失效单个缓存项。
     *
     * @param key 要失效的键，不能为 null
     * @return true 如果该 key 存在且被失效
     * @throws NullPointerException key 为 null
     */
    boolean invalidate(K key);

    /**
     * 失效指定 keys 的缓存项。
     *
     * @param keys 要失效的键集合，不能为 null
     * @throws NullPointerException keys 为 null
     */
    void invalidateAll(Set<?> keys);

    /**
     * 清空全部缓存。
     */
    void clear();

    // ========== 运维 ==========

    /**
     * 当前条目数（近似值）。
     *
     * @return 近似条目数，无法估计时返回 -1
     */
    long estimatedSize();

    /**
     * 缓存统计信息快照。
     *
     * @return 统计快照，实现不支持统计时返回 {@link CacheStats#EMPTY}
     */
    CacheStats stats();
}
