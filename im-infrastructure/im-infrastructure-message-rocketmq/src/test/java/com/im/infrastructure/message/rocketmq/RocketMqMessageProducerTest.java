package com.im.infrastructure.message.rocketmq;

import com.im.config.Config;
import com.im.infrastructure.message.MessageBusException;
import com.im.infrastructure.message.MessageEnvelope;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocketMqMessageProducerTest {

    @Test
    void mapsEnvelopeToRocketMqMessage() {
        Instant createdAt = Instant.parse("2026-06-06T10:15:30Z");
        byte[] payload = "hello rocketmq".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope envelope = MessageEnvelope.builder()
                .channel("im-deliver")
                .eventType("message.created")
                .messageKey("msg-001")
                .businessKey("single_1_2")
                .contentType("application/json")
                .createdAt(createdAt)
                .payload(payload)
                .header("traceId", "trace-001")
                .build();

        Message message = RocketMqMessageProducer.toRocketMessage(envelope);

        assertEquals("im-deliver", message.getTopic());
        assertEquals("msg-001", message.getKeys());
        assertArrayEquals(payload, message.getBody());
        assertEquals("application/json", message.getUserProperty(RocketMqMessageProducer.PROPERTY_CONTENT_TYPE));
        assertEquals("message.created", message.getUserProperty(RocketMqMessageProducer.PROPERTY_EVENT_TYPE));
        assertEquals("single_1_2", message.getUserProperty(RocketMqMessageProducer.PROPERTY_BUSINESS_KEY));
        assertEquals(String.valueOf(createdAt.toEpochMilli()), message.getUserProperty(RocketMqMessageProducer.PROPERTY_CREATED_AT));
        assertEquals("trace-001", message.getUserProperty("traceId"));
    }

    @Test
    void publishesEachEnvelopeThroughSender() {
        RecordingSender sender = new RecordingSender();
        RocketMqMessageProducer producer = new RocketMqMessageProducer(sender, Duration.ofSeconds(3));
        MessageEnvelope first = envelope("topic-a", "key-a", "a");
        MessageEnvelope second = envelope("topic-a", "key-b", "b");

        producer.publishBatch(List.of(first, second));

        assertEquals(2, sender.messages.size());
        assertEquals("key-a", sender.messages.get(0).getKeys());
        assertEquals("key-b", sender.messages.get(1).getKeys());
        assertEquals(3000L, sender.timeouts.get(0));
        assertEquals(3000L, sender.timeouts.get(1));
    }

    @Test
    void emptyBatchDoesNothing() {
        RecordingSender sender = new RecordingSender();
        RocketMqMessageProducer producer = new RocketMqMessageProducer(sender, Duration.ofSeconds(1));

        producer.publishBatch(List.of());
        producer.publishBatch(null);

        assertTrue(sender.messages.isEmpty());
    }

    @Test
    void nonOkSendStatusIsTreatedAsFailure() {
        RecordingSender sender = new RecordingSender();
        sender.status = SendStatus.FLUSH_DISK_TIMEOUT;
        RocketMqMessageProducer producer = new RocketMqMessageProducer(sender, Duration.ofSeconds(1));

        MessageBusException ex = assertThrows(MessageBusException.class,
                () -> producer.publish(envelope("topic-a", "key-a", "a")));

        assertTrue(ex.getMessage().contains("FLUSH_DISK_TIMEOUT"));
    }

    @Test
    void senderExceptionIsWrapped() {
        RecordingSender sender = new RecordingSender();
        sender.failure = new MQClientException("broker unavailable", null);
        RocketMqMessageProducer producer = new RocketMqMessageProducer(sender, Duration.ofSeconds(1));

        MessageBusException ex = assertThrows(MessageBusException.class,
                () -> producer.publish(envelope("topic-a", "key-a", "a")));

        assertTrue(ex.getMessage().contains("RocketMQ publish failed"));
        assertSame(sender.failure, ex.getCause());
    }

    @Test
    void delayedPublishUsesRocketMqTimerMessage() {
        RecordingSender sender = new RecordingSender();
        RocketMqMessageProducer producer = new RocketMqMessageProducer(sender, Duration.ofSeconds(1));

        producer.publishDelayed(envelope("topic-a", "key-a", "a"), 1500L);

        assertEquals(1, sender.messages.size());
        long deliverAt = sender.messages.getFirst().getDeliverTimeMs();
        assertTrue(deliverAt >= System.currentTimeMillis());
        assertTrue(deliverAt <= System.currentTimeMillis() + 3000L);
    }

    @Test
    void createsProducerFromConfig() {
        Config config = new TestConfig(Map.of(
                "im.rocketmq.name-server", "127.0.0.1:9876",
                "im.rocketmq.producer.group", "im-producer-test",
                "im.rocketmq.send.timeout-ms", "2500",
                "im.rocketmq.retry-times", "4"
        ));

        RocketMqProducerProperties properties = RocketMqProducerProperties.from(config);

        assertEquals("127.0.0.1:9876", properties.nameServer());
        assertEquals("im-producer-test", properties.producerGroup());
        assertEquals(Duration.ofMillis(2500), properties.sendTimeout());
        assertEquals(4, properties.retryTimesWhenSendFailed());
    }

    private static MessageEnvelope envelope(String topic, String key, String body) {
        return MessageEnvelope.builder()
                .channel(topic)
                .messageKey(key)
                .payload(body.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private static final class RecordingSender implements RocketMqMessageSender {
        private final List<Message> messages = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();
        private SendStatus status = SendStatus.SEND_OK;
        private Exception failure;

        @Override
        public SendResult send(Message message, long timeoutMillis) throws Exception {
            if (failure != null) throw failure;
            messages.add(message);
            timeouts.add(timeoutMillis);
            return new SendResult(status, "msg-id", null, null, 0L);
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }
    }

    private record TestConfig(Map<String, String> values) implements Config {
        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return getString(key).map(Integer::parseInt);
        }

        @Override
        public Optional<Long> getLong(String key) {
            return getString(key).map(Long::parseLong);
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return getString(key).map(Boolean::parseBoolean);
        }

        @Override
        public Optional<Duration> getDuration(String key) {
            return getString(key).map(Duration::parse);
        }

        @Override
        public boolean hasKey(String key) {
            return values.containsKey(key);
        }
    }
}
