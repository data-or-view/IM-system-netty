package com.im.infrastructure.message;

import java.util.Collection;
import java.util.List;

/**
 * 消息生产者。
 *
 * <p>业务侧只依赖这个接口发送消息，不感知底层是内存、RocketMQ 还是 Kafka。
 * 对应 OpenIM 的 {@code mq.Producer} 和 cinema 的 {@code MessageBus} 的生产者侧。
 *
 * <p>批量发送是首选方式（cinema 的设计理念：批量接口更容易做吞吐优化，
 * 单条发送只是批量的退化形式），因此默认提供了单条的便捷方法。
 */
public interface MessageProducer {

    /**
     * 批量发送消息。
     */
    void publishBatch(Collection<MessageEnvelope> envelopes);

    /**
     * 批量发送延迟消息。
     */
    void publishDelayedBatch(Collection<MessageEnvelope> envelopes, long delayMillis);

    /**
     * 发送单条消息（批量接口的便捷包装）。
     */
    default void publish(MessageEnvelope envelope) {
        publishBatch(List.of(envelope));
    }

    /**
     * 发送单条延迟消息。
     */
    default void publishDelayed(MessageEnvelope envelope, long delayMillis) {
        publishDelayedBatch(List.of(envelope), delayMillis);
    }
}
