package com.im.api;

import com.im.common.lifecycle.Lifecycle;

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
 *   │ MemoryQueue  │ 单机开发测试                  │
 *   │ RedisQueue   │ 生产集群，Redis Streams       │
 *   │ KafkaQueue   │ 生产集群，高吞吐               │
 *   └──────────────┴──────────────────────────────┘
 */
public interface IMessageQueue extends Lifecycle {

    @Override
    void start() throws Exception;

    @Override
    void stop();

    /**
     * 生产消息到指定 topic。
     */
    void publishAsync(String topic, Message msg);

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
        void onMessage(Message msg);
    }
}
