package com.im.api;

/**
 * 集群状态存储（分布式 KV）。
 *
 * 存储集群共享数据：
 *   · 群组信息（IGroupManager 的底层存储）
 *   · 用户状态（认证 token、在线标记）
 *   · 分布式配置
 *
 * 实现选择：
 *   ┌──────────────┬─────────────────────────────────┐
 *   │ 部署模式     │ 实现                            │
 *   ├──────────────┼─────────────────────────────────┤
 *   │ 单机         │ LocalStateStore (ConcurrentHashMap)│
 *   │ 集群         │ RedisStateStore / EtcdStateStore│
 *   └──────────────┴─────────────────────────────────┘
 */
public interface IClusterStateStore extends ILifecycle {

    /**
     * 写入 KV。
     * namespace 用于隔离不同类型的数据（如 "group", "user"）。
     */
    void put(String namespace, String key, String value);

    /**
     * 读取 KV。
     */
    String get(String namespace, String key);

    /**
     * 删除 KV。
     */
    void delete(String namespace, String key);

    /**
     * 监听指定前缀的 key 变更。
     * keyPrefix 为 null 时监听整个 namespace。
     */
    void watch(String namespace, String keyPrefix, StateChangeListener listener);

    /**
     * 取消监听。
     */
    void unwatch(String namespace, StateChangeListener listener);

    /** 状态变更监听器 */
    @FunctionalInterface
    interface StateChangeListener {
        void onChange(String namespace, String key, String newValue);
    }
}
