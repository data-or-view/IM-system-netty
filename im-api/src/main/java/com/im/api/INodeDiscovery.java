package com.im.api;

import java.util.List;

/**
 * 集群节点发现与拓扑管理。
 *
 * 职责：
 *   · 本节点向集群注册（上线/下线）
 *   · 获取当前在线节点列表
 *   · 监听节点变更（新增/移除）
 *
 * 实现参考 RocketMQ NameServer 的 RouteInfoManager：
 *   · brokerAddrTable (brokerName → BrokerData)  = 节点信息表
 *   · brokerLiveTable (brokerAddr → BrokerLiveInfo) = 心跳表
 *   · scanNotActiveBroker() 定时扫描过期节点
 *
 * 实现选择：
 *   ┌──────────────┬─────────────────────────────────┐
 *   │ 部署模式     │ 实现                            │
 *   ├──────────────┼─────────────────────────────────┤
 *   │ 单机         │ LocalNodeDiscovery (只有自己)   │
 *   │ 开发测试     │ StaticNodeDiscovery (配置文件)  │
 *   │ 生产         │ RedisNodeDiscovery / EtcdImpl   │
 *   └──────────────┴─────────────────────────────────┘
 */
public interface INodeDiscovery extends ILifecycle {

    /**
     * 注册本节点到集群。
     * 上线时调用，后续通过心跳维持在线状态。
     */
    void register(NodeInformation self);

    /**
     * 从集群注销（优雅关闭时调用）。
     */
    void unregister();

    /**
     * 主动心跳/刷新租约。
     * 定时调用（默认 10s），实现层根据 lease 机制决定是否需要。
     */
    default void heartbeat() {
        // 默认空实现
    }

    /**
     * 获取当前在线节点列表（含自身）。
     */
    List<NodeInformation> aliveNodes();

    /**
     * 获取指定节点的信息。
     */
    NodeInformation getNode(String nodeId);

    /**
     * 注册节点变更监听器。
     */
    void addListener(NodeEventListener listener);

    /**
     * 节点变更事件。
     */
    @FunctionalInterface
    interface NodeEventListener {
        void onEvent(Event event);

        enum EventType { NODE_ADDED, NODE_REMOVED, NODE_UPDATED }

        class Event {
            private final EventType type;
            private final NodeInformation node;

            public Event(EventType type, NodeInformation node) {
                this.type = type;
                this.node = node;
            }

            public EventType getType() { return type; }
            public NodeInformation getNode() { return node; }
        }
    }
}
