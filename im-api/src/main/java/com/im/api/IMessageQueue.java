package com.im.api;

import java.util.Set;

/**
 * 消息队列抽象接口。
 *
 * 用于解耦"消息接收"和"消息投递/持久化"。
 * ChatHandler publish 后立刻回 ACK，Consumer 异步处理。
 *
 * 消息中间件可插拔：
 *   ┌──────────────┬──────────────────────────────┐
 *   │ 方案         │ 场景                         │
 *   ├──────────────┼──────────────────────────────┤
 *   │ MemoryQueue  │ 单机开发测试（基于BQingQueue） │
 *   │ KafkaQueue   │ 生产集群，高吞吐               │
 *   │ RocketMQQueue│ 生产集群，事务消息              │
 *   │ PulsarQueue  │ 生产集群，多租户               │
 *   └──────────────┴──────────────────────────────┘
 */
public interface IMessageQueue extends ILifecycle {

    /**
     * 生产消息到指定 topic。
     * 实现层负责序列化 + 投递。
     */
    void publishAsync(String topic, IMCommand msg);

    /**
     * 订阅指定 topic。
     * 同一个 topic 允许多个消费者（各自收到消息）。
     */
    void subscribe(String topic, MessageHandler handler);

    /**
     * 取消订阅。
     */
    void unsubscribe(String topic, MessageHandler handler);

    /**
     * 检查 topic 是否有消费者。
     */
    boolean hasSubscribers(String topic);

    /**
     * 获取所有 topic。
     */
    default Set<String> topics() {
        return Set.of();
    }

    /**
     * 消息处理器。
     */
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(IMCommand msg);
    }
}
