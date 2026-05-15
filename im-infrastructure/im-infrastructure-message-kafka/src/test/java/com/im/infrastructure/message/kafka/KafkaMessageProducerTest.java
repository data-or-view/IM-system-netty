package com.im.infrastructure.message.kafka;

import com.im.infrastructure.message.MessageEnvelope;
import com.im.infrastructure.message.MessageProducer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaMessageProducerTest {

    private static final String BOOTSTRAP_SERVERS = "127.0.0.1:9092";
    private static final String TEST_TOPIC = "test-producer";

    private KafkaMessageProducer producer;
    private KafkaConsumer<String, byte[]> consumer;

    @BeforeAll
    void setUp() {
        producer = new KafkaMessageProducer(BOOTSTRAP_SERVERS, Duration.ofSeconds(5));
        consumer = createConsumer();
        consumer.subscribe(List.of(TEST_TOPIC));
    }

    @AfterAll
    void tearDown() {
        if (producer != null) producer.close();
        if (consumer != null) consumer.close();
    }

    @BeforeEach
    void drainTopic() {
        // drain any leftover messages before each test
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(200));
            if (records.isEmpty()) break;
        }
    }

    @Test
    void publishMessage() {
        byte[] payload = "hello kafka".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope env = MessageEnvelope.builder()
                .channel(TEST_TOPIC)
                .messageKey("key-001")
                .payload(payload)
                .header("traceId", "trace-001")
                .build();

        producer.publish(env);

        // verify from Kafka
        List<MessageEnvelope> received = poll(1);
        assertEquals(1, received.size());
        MessageEnvelope result = received.get(0);
        assertEquals(TEST_TOPIC, result.getChannel());
        assertEquals("key-001", result.getMessageKey());
        assertArrayEquals(payload, result.getPayload());
    }

    @Test
    void publishBatch() {
        List<MessageEnvelope> batch = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch.add(MessageEnvelope.builder()
                    .channel(TEST_TOPIC)
                    .messageKey("batch-key-" + i)
                    .payload(("msg-" + i).getBytes(StandardCharsets.UTF_8))
                    .build());
        }

        producer.publishBatch(batch);

        List<MessageEnvelope> received = poll(3);
        assertEquals(3, received.size());
    }

    @Test
    void publishDelayedThrows() {
        MessageEnvelope env = MessageEnvelope.builder()
                .channel(TEST_TOPIC)
                .payload("test".getBytes(StandardCharsets.UTF_8))
                .build();

        assertThrows(RuntimeException.class, () ->
                producer.publishDelayed(env, 1000));
    }

    @Test
    void publishEmptyBatchDoesNothing() {
        producer.publishBatch(List.of());
        assertTrue(true);
    }

    // ── helpers ──

    private List<MessageEnvelope> poll(int expectedCount) {
        List<MessageEnvelope> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10000;
        while (results.size() < expectedCount && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            records.forEach(record -> {
                Map<String, String> headers = new HashMap<>();
                record.headers().forEach(h -> headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));

                MessageEnvelope env = MessageEnvelope.builder()
                        .channel(record.topic())
                        .messageKey(record.key())
                        .payload(record.value())
                        .headers(headers)
                        .build();
                results.add(env);
            });
        }
        return results;
    }

    private static KafkaConsumer<String, byte[]> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
