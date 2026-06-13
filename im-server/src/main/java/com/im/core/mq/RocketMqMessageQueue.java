package com.im.core.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.config.Config;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RocketMQ implementation of the IM message queue abstraction.
 *
 * <p>This is intentionally a thin adapter: IM code still uses logical topics
 * such as {@code persist} and {@code deliver}; this class maps them to RocketMQ
 * topics and translates consumer failures into RocketMQ retry semantics.</p>
 */
public class RocketMqMessageQueue implements IMessageQueue {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageQueue.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private static final String DEFAULT_PRODUCER_GROUP = "im-producer";
    private static final String DEFAULT_CONSUMER_GROUP_PREFIX = "im-consumer";
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(3);

    private final Properties properties;
    private final String nodeId;
    private final ConcurrentHashMap<String, List<MessageHandler>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultMQPushConsumer> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DefaultMQProducer producer;

    public RocketMqMessageQueue(Config config, String nodeId) {
        this(Properties.from(config), nodeId);
    }

    RocketMqMessageQueue(Properties properties, String nodeId) {
        this.properties = properties;
        this.nodeId = nodeId;
    }

    @Override
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;

        producer = new DefaultMQProducer(properties.producerGroup());
        producer.setNamesrvAddr(properties.nameServer());
        producer.setSendMsgTimeout(Math.toIntExact(properties.sendTimeout().toMillis()));
        producer.setRetryTimesWhenSendFailed(properties.retryTimesWhenSendFailed());
        producer.start();

        for (String topic : subscribers.keySet()) {
            startConsumer(topic);
        }
        log.info("RocketMqMessageQueue started: nodeId={}, nameServer={}", nodeId, properties.nameServer());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        List<DefaultMQPushConsumer> runningConsumers = new ArrayList<>(consumers.values());
        consumers.clear();
        for (DefaultMQPushConsumer consumer : runningConsumers) {
            consumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
        log.info("RocketMqMessageQueue stopped: nodeId={}", nodeId);
    }

    @Override
    public void publishAsync(String topic, Message msg) {
        if (!running.get() || producer == null) {
            throw new IllegalStateException("RocketMQ queue not running for topic " + topic);
        }

        try {
            org.apache.rocketmq.common.message.Message rocketMessage = toRocketMessage(topic, msg);
            SendResult result = producer.send(rocketMessage, properties.sendTimeout().toMillis());
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ send status " + result.getSendStatus());
            }
            log.trace("Published to RocketMQ topic={}, key={}, msgId={}",
                    rocketMessage.getTopic(), rocketMessage.getKeys(), result.getMsgId());
        } catch (Exception e) {
            throw new IllegalStateException("RocketMQ publish failed for topic " + topic, e);
        }
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (running.get()) {
            startConsumer(topic);
        }
        log.info("RocketMQ handler subscribed to topic '{}'", topic);
    }

    @Override
    public void unsubscribe(String topic, MessageHandler handler) {
        List<MessageHandler> handlers = subscribers.get(topic);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                subscribers.remove(topic);
                DefaultMQPushConsumer consumer = consumers.remove(topic);
                if (consumer != null) {
                    consumer.shutdown();
                }
            }
        }
        log.info("RocketMQ handler unsubscribed from topic '{}'", topic);
    }

    @Override
    public boolean hasSubscribers(String topic) {
        List<MessageHandler> handlers = subscribers.get(topic);
        return handlers != null && !handlers.isEmpty();
    }

    @Override
    public Set<String> topics() {
        return subscribers.keySet();
    }

    public String physicalTopic(String logicalTopic) {
        return properties.topicPrefix() + logicalTopic;
    }

    private void startConsumer(String logicalTopic) {
        consumers.computeIfAbsent(logicalTopic, topic -> {
            try {
                DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup(topic));
                consumer.setNamesrvAddr(properties.nameServer());
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
                consumer.subscribe(physicalTopic(topic), "*");
                consumer.registerMessageListener(new Listener(topic));
                consumer.start();
                log.info("RocketMQ consumer started: topic={}, group={}", physicalTopic(topic), consumerGroup(topic));
                return consumer;
            } catch (MQClientException e) {
                throw new IllegalStateException("RocketMQ consumer start failed for topic " + topic, e);
            }
        });
    }

    private String consumerGroup(String logicalTopic) {
        return properties.consumerGroupPrefix() + "-" + logicalTopic;
    }

    private org.apache.rocketmq.common.message.Message toRocketMessage(String logicalTopic, Message msg) throws Exception {
        byte[] payload = MAPPER.writeValueAsBytes(msg.toJsonMap());
        org.apache.rocketmq.common.message.Message rocketMessage =
                new org.apache.rocketmq.common.message.Message(physicalTopic(logicalTopic), payload);
        if (msg.getMessageId() != null && !msg.getMessageId().isBlank()) {
            rocketMessage.setKeys(msg.getMessageId());
        }
        rocketMessage.putUserProperty("logicalTopic", logicalTopic);
        rocketMessage.putUserProperty("nodeId", nodeId);
        return rocketMessage;
    }

    private static Message fromRocketMessage(MessageExt message) throws Exception {
        Map<String, Object> map = MAPPER.readValue(
                new String(message.getBody(), StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {});
        return Message.fromJsonMap(map);
    }

    private final class Listener implements MessageListenerConcurrently {
        private final String logicalTopic;

        private Listener(String logicalTopic) {
            this.logicalTopic = logicalTopic;
        }

        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                       ConsumeConcurrentlyContext context) {
            List<MessageHandler> handlers = subscribers.get(logicalTopic);
            if (handlers == null || handlers.isEmpty()) {
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }

            for (MessageExt ext : messages) {
                try {
                    Message msg = fromRocketMessage(ext);
                    for (MessageHandler handler : handlers) {
                        handler.onMessage(msg);
                    }
                } catch (Exception e) {
                    log.error("RocketMQ consume failed: topic={}, msgId={}, reconsumeTimes={}",
                            logicalTopic, ext.getMsgId(), ext.getReconsumeTimes(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
    }

    record Properties(String nameServer,
                      String producerGroup,
                      String consumerGroupPrefix,
                      String topicPrefix,
                      Duration sendTimeout,
                      int retryTimesWhenSendFailed) {

        static Properties from(Config config) {
            String nameServer = config.getRequiredString("im.rocketmq.name-server");
            String producerGroup = config.getString("im.rocketmq.producer.group", DEFAULT_PRODUCER_GROUP);
            String consumerGroupPrefix = config.getString(
                    "im.rocketmq.consumer.group-prefix", DEFAULT_CONSUMER_GROUP_PREFIX);
            String topicPrefix = config.getString("im.rocketmq.topic-prefix", "");
            Duration sendTimeout = config.getLong("im.rocketmq.send.timeout-ms")
                    .map(Duration::ofMillis)
                    .orElse(DEFAULT_SEND_TIMEOUT);
            int retries = config.getInt("im.rocketmq.retry-times", 2);
            return new Properties(nameServer, producerGroup, consumerGroupPrefix, topicPrefix, sendTimeout, retries);
        }

        Properties {
            if (nameServer == null || nameServer.isBlank()) {
                throw new IllegalArgumentException("RocketMQ nameServer must not be blank");
            }
            if (producerGroup == null || producerGroup.isBlank()) {
                throw new IllegalArgumentException("RocketMQ producerGroup must not be blank");
            }
            if (consumerGroupPrefix == null || consumerGroupPrefix.isBlank()) {
                throw new IllegalArgumentException("RocketMQ consumerGroupPrefix must not be blank");
            }
            if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
                throw new IllegalArgumentException("RocketMQ sendTimeout must be positive");
            }
            if (retryTimesWhenSendFailed < 0) {
                throw new IllegalArgumentException("RocketMQ retryTimesWhenSendFailed must not be negative");
            }
        }
    }
}
