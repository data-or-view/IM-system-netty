package com.im.bootstrap;

import com.im.api.IMessageQueue;
import com.im.config.Config;
import com.im.core.mq.RedisMessageQueue;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageQueueSelectionTest {

    @Test
    void defaultsToRedisMessageQueue() {
        IMessageQueue queue = ServerComponentsFactory.createMessageQueue(
                new TestConfig(Map.of()), null, "node-a");

        assertInstanceOf(RedisMessageQueue.class, queue);
    }

    @Test
    void createsRocketMqMessageQueueWhenConfigured() {
        IMessageQueue queue = ServerComponentsFactory.createMessageQueue(
                new TestConfig(Map.of(
                        "im.mq.type", "rocketmq",
                        "im.rocketmq.name-server", "127.0.0.1:9876"
                )), null, "node-a");

        assertInstanceOf(RocketMqMessageQueue.class, queue);
    }

    @Test
    void rejectsUnknownMessageQueueType() {
        assertThrows(IllegalArgumentException.class, () -> ServerComponentsFactory.createMessageQueue(
                new TestConfig(Map.of("im.mq.type", "kafka")), null, "node-a"));
    }

    private record TestConfig(Map<String, String> values) implements Config {
        @Override public Optional<String> getString(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public Optional<Integer> getInt(String key) { return Optional.ofNullable(values.get(key)).map(Integer::parseInt); }
        @Override public Optional<Long> getLong(String key) { return Optional.ofNullable(values.get(key)).map(Long::parseLong); }
        @Override public Optional<Boolean> getBoolean(String key) { return Optional.ofNullable(values.get(key)).map(Boolean::parseBoolean); }
        @Override public Optional<Duration> getDuration(String key) { return Optional.empty(); }
        @Override public boolean hasKey(String key) { return values.containsKey(key); }
    }
}
