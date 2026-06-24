package com.im.infrastructure.message.rocketmq;

import com.im.common.lifecycle.Lifecycle;
import com.im.infrastructure.message.MessageBusException;
import com.im.infrastructure.message.MessageEnvelope;
import com.im.infrastructure.message.MessageProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * RocketMQ producer for the infrastructure message abstraction.
 *
 * <p>This producer is retained for the infrastructure message abstraction. IM server message
 * delivery uses {@link RocketMqMessageQueue}, the infrastructure implementation of
 * {@code IMessageQueue}, so RocketMQ SDK details stay outside the server module.
 */
public class RocketMqMessageProducer implements MessageProducer, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageProducer.class);

    public static final String PROPERTY_CONTENT_TYPE = "_contentType";
    public static final String PROPERTY_EVENT_TYPE = "_eventType";
    public static final String PROPERTY_BUSINESS_KEY = "_businessKey";
    public static final String PROPERTY_CREATED_AT = "_createdAt";

    private final RocketMqMessageSender sender;
    private final Duration sendTimeout;

    public RocketMqMessageProducer(RocketMqProducerProperties properties) {
        this(new DefaultRocketMqMessageSender(properties), properties.sendTimeout());
    }

    RocketMqMessageProducer(RocketMqMessageSender sender, Duration sendTimeout) {
        if (sender == null) throw new IllegalArgumentException("sender must not be null");
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sendTimeout must be positive");
        }
        this.sender = sender;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publishBatch(Collection<MessageEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) return;

        for (MessageEnvelope envelope : envelopes) {
            send(toRocketMessage(envelope));
        }
    }

    @Override
    public void publishDelayedBatch(Collection<MessageEnvelope> envelopes, long delayMillis) {
        if (envelopes == null || envelopes.isEmpty()) return;
        if (delayMillis < 0) throw new IllegalArgumentException("delayMillis must not be negative");

        long deliverAtMillis = System.currentTimeMillis() + delayMillis;
        for (MessageEnvelope envelope : envelopes) {
            Message message = toRocketMessage(envelope);
            // RocketMQ 5.x supports millisecond timer messages; using it keeps this producer
            // faithful to MessageProducer instead of leaking delay-level assumptions to callers.
            message.setDeliverTimeMs(deliverAtMillis);
            send(message);
        }
    }

    static Message toRocketMessage(MessageEnvelope envelope) {
        Message message = new Message(envelope.getChannel(), envelope.getPayload());
        if (envelope.getMessageKey() != null && !envelope.getMessageKey().isBlank()) {
            message.setKeys(envelope.getMessageKey());
        }
        putProperty(message, PROPERTY_CONTENT_TYPE, envelope.getContentType());
        putProperty(message, PROPERTY_EVENT_TYPE, envelope.getEventType());
        putProperty(message, PROPERTY_BUSINESS_KEY, envelope.getBusinessKey());
        if (envelope.getCreatedAt() != null) {
            putProperty(message, PROPERTY_CREATED_AT, String.valueOf(envelope.getCreatedAt().toEpochMilli()));
        }
        if (envelope.getHeaders() != null) {
            for (Map.Entry<String, String> entry : envelope.getHeaders().entrySet()) {
                putProperty(message, entry.getKey(), entry.getValue());
            }
        }
        return message;
    }

    private void send(Message message) {
        try {
            SendResult result = sender.send(message, sendTimeout.toMillis());
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new MessageBusException("RocketMQ publish failed with status " + result.getSendStatus());
            }
            log.debug("Published to RocketMQ topic={} key={} msgId={}",
                    message.getTopic(), message.getKeys(), result.getMsgId());
        } catch (MessageBusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageBusException("RocketMQ publish interrupted", e);
        } catch (Exception e) {
            throw new MessageBusException("RocketMQ publish failed: " + e.getMessage(), e);
        }
    }

    private static void putProperty(Message message, String key, String value) {
        if (key != null && value != null) {
            message.putUserProperty(key, value);
        }
    }

    @Override
    public void start() {
        try {
            sender.start();
        } catch (Exception e) {
            throw new MessageBusException("RocketMQ producer start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        sender.shutdown();
        log.info("RocketMqMessageProducer closed");
    }
}
