package com.im.api;

import com.im.common.lifecycle.Lifecycle;

/**
 * 集群消息总线（节点间通信）。
 *
 * RocketMQ 的节点间不需要转发（Producer 直连目标 Broker），
 * 但 IM 场景必须：用户 A 在 node1，用户 B 在 node2，消息必须 node1→node2。
 *
 * 通信方式（实现可插拔）：
 *   ┌──────────────┬─────────────────────────────────┐
 *   │ 方案         │ 场景                            │
 *   ├──────────────┼─────────────────────────────────┤
 *   │ Netty P2P    │ 节点数 < 50，低延迟            │
 *   │ 共享 MQ      │ 节点数 > 50，解耦               │
 *   │ gRPC stream  │ 需要多语言互通                   │
 *   └──────────────┴─────────────────────────────────┘
 *
 * 消息防环路：ClusterMessage 自带 TTL，每跳自动递减。
 */
public interface IClusterMessageBus extends Lifecycle {

    @Override
    void start() throws Exception;

    @Override
    void stop();

    /**
     * 发送消息到指定节点。
     * 用于用户消息跨节点转发。
     */
    void sendToNode(ClusterMessage msg, String targetNodeId);

    /**
     * 广播消息到所有在线节点（不含自身）。
     * 用于：用户下线通知、群聊广播、状态同步。
     */
    void broadcast(ClusterMessage msg);

    /**
     * 订阅指定类型的集群消息。
     */
    void subscribe(String topic, ClusterMessageHandler handler);

    /**
     * 取消订阅。
     */
    void unsubscribe(String topic, ClusterMessageHandler handler);
}
