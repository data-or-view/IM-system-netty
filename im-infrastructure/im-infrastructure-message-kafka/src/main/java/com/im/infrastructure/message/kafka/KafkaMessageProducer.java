package com.im.infrastructure.message.kafka;

import com.im.common.lifecycle.Lifecycle;
import com.im.infrastructure.message.MessageBusException;
import com.im.infrastructure.message.MessageEnvelope;
import com.im.infrastructure.message.MessageProducer;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Kafka 的 {@link MessageProducer} 实现。
 *
 * <p>映射规则：
 * <ul>
 *   <li>{@code envelope.channel} → Kafka topic</li>
 *   <li>{@code envelope.messageKey} → Kafka record key（用于分区有序性）</li>
 *   <li>{@code envelope.payload} → Kafka record value</li>
 *   <li>{@code envelope.headers} → Kafka record headers</li>
 * </ul>
 *
 * <p>延迟消息暂不支持（Kafka 原生无延迟语义，需要额外机制）。
 */
public class KafkaMessageProducer implements MessageProducer, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageProducer.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    public static final String HEADER_CONTENT_TYPE = "_contentType";
    public static final String HEADER_EVENT_TYPE = "_eventType";
    public static final String HEADER_BUSINESS_KEY = "_businessKey";
    public static final String HEADER_CREATED_AT = "_createdAt";

    private final KafkaProducer<String, byte[]> producer;
    private final Duration publishTimeout;

    public KafkaMessageProducer(String bootstrapServers) {
        this(bootstrapServers, DEFAULT_TIMEOUT);
    }

    public KafkaMessageProducer(String bootstrapServers, Duration timeout) {
        this.producer = createProducer(bootstrapServers);
        this.publishTimeout = timeout;
    }

    KafkaMessageProducer(KafkaProducer<String, byte[]> producer, Duration timeout) {
        this.producer = producer;
        this.publishTimeout = timeout;
    }

    private static KafkaProducer<String, byte[]> createProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new KafkaProducer<>(props);
    }

    @Override
    public void publishBatch(Collection<MessageEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) return;

        for (MessageEnvelope envelope : envelopes) {
            ProducerRecord<String, byte[]> record = toProducerRecord(envelope);
            try {
                RecordMetadata metadata = producer.send(record).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
                log.debug("Published to topic={} partition={} offset={} key={}",
                        metadata.topic(), metadata.partition(), metadata.offset(), envelope.getMessageKey());
            } catch (ExecutionException e) {
                throw new MessageBusException("Kafka publish failed: " + e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessageBusException("Kafka publish interrupted", e);
            } catch (TimeoutException e) {
                throw new MessageBusException("Kafka publish timed out after " + publishTimeout.toMillis() + "ms", e);
            }
        }
    }

    @Override
    public void publishDelayedBatch(Collection<MessageEnvelope> envelopes, long delayMillis) {
        throw new MessageBusException("Delayed messages not supported in Kafka implementation");
    }

    private ProducerRecord<String, byte[]> toProducerRecord(MessageEnvelope envelope) {
        String topic = envelope.getChannel();
        String key = envelope.getMessageKey();
        byte[] value = envelope.getPayload();

        org.apache.kafka.common.header.Headers headers = new RecordHeaders();
        if (envelope.getContentType() != null) {
            headers.add(new RecordHeader(HEADER_CONTENT_TYPE, envelope.getContentType().getBytes(StandardCharsets.UTF_8)));
        }
        if (envelope.getEventType() != null) {
            headers.add(new RecordHeader(HEADER_EVENT_TYPE, envelope.getEventType().getBytes(StandardCharsets.UTF_8)));
        }
        if (envelope.getBusinessKey() != null) {
            headers.add(new RecordHeader(HEADER_BUSINESS_KEY, envelope.getBusinessKey().getBytes(StandardCharsets.UTF_8)));
        }
        if (envelope.getCreatedAt() != null) {
            headers.add(new RecordHeader(HEADER_CREATED_AT, String.valueOf(envelope.getCreatedAt().toEpochMilli()).getBytes(StandardCharsets.UTF_8)));
        }
        if (envelope.getHeaders() != null) {
            for (Map.Entry<String, String> entry : envelope.getHeaders().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.add(new RecordHeader(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
                }
            }
        }

        return new ProducerRecord<>(topic, null, null, key, value, headers);
    }

    // ========== 生命周期 ==========

    @Override
    public void start() {
        // producer 已在构造函数中创建，无额外初始化
    }

    @Override
    public void stop() {
        close();
    }

    /** 关闭 producer，释放资源 */
    public void close() {
        producer.close(DEFAULT_TIMEOUT);
        log.info("KafkaMessageProducer closed");
    }
}
