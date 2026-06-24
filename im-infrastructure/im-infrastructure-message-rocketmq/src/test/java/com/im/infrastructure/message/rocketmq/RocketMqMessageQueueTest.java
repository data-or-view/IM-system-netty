package com.im.infrastructure.message.rocketmq;

import com.im.api.Message;
import com.im.config.Config;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RocketMqMessageQueueTest {

    @Test
    void mapsConfiguredConsumeFromWhereToRocketMqConsumerPolicy() {
        RocketMqMessageQueueProperties properties = RocketMqMessageQueueProperties.from(new TestConfig(Map.of(
                RocketMqMessageQueueProperties.KEY_NAME_SERVER, "127.0.0.1:9876",
                RocketMqMessageQueueProperties.KEY_CONSUME_FROM_WHERE, "CONSUME_FROM_FIRST_OFFSET"
        )));
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties, "node-a");

        assertEquals(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET, properties.consumeFromWhere());
        assertEquals(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET, queue.consumeFromWhereForTest());
    }

    @Test
    void rejectsInvalidConsumeFromWhere() {
        TestConfig config = new TestConfig(Map.of(
                RocketMqMessageQueueProperties.KEY_NAME_SERVER, "127.0.0.1:9876",
                RocketMqMessageQueueProperties.KEY_CONSUME_FROM_WHERE, "from-the-middle"
        ));

        assertThrows(IllegalArgumentException.class, () -> RocketMqMessageQueueProperties.from(config));
    }

    @Test
    void mapsImMessageToRocketMqMessageWithTraceableProperties() throws Exception {
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties("im-"), "node-a");
        Message message = message("msg-1", "single_alice_bob", 42);

        org.apache.rocketmq.common.message.Message rocketMessage =
                queue.toRocketMessageForTest("deliver", message);

        assertEquals("im-deliver", rocketMessage.getTopic());
        assertEquals("msg-1", rocketMessage.getKeys());
        assertEquals("deliver", rocketMessage.getUserProperty("logicalTopic"));
        assertEquals("node-a", rocketMessage.getUserProperty("nodeId"));
        assertEquals("single_alice_bob", rocketMessage.getUserProperty("conversationId"));
        assertEquals("42", rocketMessage.getUserProperty("messageSeq"));
    }

    @Test
    void listenerReturnsReconsumeLaterWhenHandlerThrows() {
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties(""), "node-a");
        queue.subscribe("deliver", msg -> {
            throw new IllegalStateException("push failed");
        });

        ConsumeConcurrentlyStatus status = queue.consumeForTest("deliver", List.of(rocketMessage(message("msg-1", "c1", 1))));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, status);
    }

    @Test
    void listenerReturnsConsumeSuccessWhenNoSubscriberExists() {
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties(""), "node-a");

        ConsumeConcurrentlyStatus status = queue.consumeForTest("deliver", List.of(rocketMessage(message("msg-1", "c1", 1))));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
    }

    @Test
    void roundTripsMessageSeqForNonMqOrderingStrategy() throws Exception {
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties(""), "node-a");
        Message original = message("msg-1", "single_alice_bob", 42);

        Message decoded = queue.fromRocketMessageForTest(rocketMessage(original));

        assertEquals("msg-1", decoded.getMessageId());
        assertEquals("single_alice_bob", decoded.getConversationId());
        assertEquals(42, decoded.getMessageSeq());
    }

    private static RocketMqMessageQueueProperties properties(String topicPrefix) {
        return new RocketMqMessageQueueProperties(
                "127.0.0.1:9876",
                "im-producer-test",
                "im-consumer-test",
                topicPrefix,
                Duration.ofSeconds(3),
                2,
                ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
    }

    private static Message message(String messageId, String conversationId, long messageSeq) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setMessageSeq(messageSeq);
        return message;
    }

    private static MessageExt rocketMessage(Message message) {
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties(""), "node-a");
        try {
            org.apache.rocketmq.common.message.Message rocket = queue.toRocketMessageForTest("deliver", message);
            MessageExt ext = new MessageExt();
            ext.setTopic(rocket.getTopic());
            ext.setBody(rocket.getBody());
            ext.setKeys(rocket.getKeys());
            return ext;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
