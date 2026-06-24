package com.im.infrastructure.message.rocketmq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.config.Config;
import com.im.infrastructure.message.MessageBusException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production RocketMQ implementation of the IM queue port.
 *
 * <p>The IM business order source is {@code Message.messageSeq}. This queue uses
 * normal RocketMQ sends and carries {@code conversationId}/{@code messageSeq}
 * as traceable properties; consumers and offline sync must order by messageSeq
 * instead of assuming broker delivery order.</p>
 *
 * <p>Consumer business failures are passed back to RocketMQ only when handlers throw.
 * The current server consumers wrap business processing with a DB-backed business DLQ
 * before this class sees the result, so offset retry and business replay are intentionally
 * separate concerns.</p>
 */
public class RocketMqMessageQueue implements IMessageQueue {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageQueue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RocketMqMessageQueueProperties properties;
    private final String nodeId;
    private final ConcurrentHashMap<String, List<MessageHandler>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultMQPushConsumer> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DefaultMQProducer producer;

    public RocketMqMessageQueue(Config config, String nodeId) {
        this(RocketMqMessageQueueProperties.from(config), nodeId);
    }

    public RocketMqMessageQueue(RocketMqMessageQueueProperties properties, String nodeId) {
        this.properties = properties;
        this.nodeId = nodeId;
    }

    @Override
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }

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
        if (!running.compareAndSet(true, false)) {
            return;
        }

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
    public void publish(String topic, Message msg) {
        if (!running.get() || producer == null) {
            throw new MessageBusException("RocketMQ queue not running for topic " + topic);
        }

        try {
            org.apache.rocketmq.common.message.Message rocketMessage = toRocketMessage(topic, msg);
            SendResult result = producer.send(rocketMessage, properties.sendTimeout().toMillis());
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new MessageBusException("RocketMQ send status " + result.getSendStatus());
            }
            log.trace("Published to RocketMQ topic={}, key={}, msgId={}",
                    rocketMessage.getTopic(), rocketMessage.getKeys(), result.getMsgId());
        } catch (MessageBusException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageBusException("RocketMQ publish failed for topic " + topic, e);
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
                consumer.setConsumeFromWhere(properties.consumeFromWhere());
                if (properties.consumeFromWhere() == ConsumeFromWhere.CONSUME_FROM_TIMESTAMP
                        && !properties.consumeTimestamp().isBlank()) {
                    consumer.setConsumeTimestamp(properties.consumeTimestamp());
                }
                consumer.subscribe(physicalTopic(topic), "*");
                consumer.registerMessageListener(new Listener(topic));
                consumer.start();
                log.info("RocketMQ consumer started: topic={}, group={}", physicalTopic(topic), consumerGroup(topic));
                return consumer;
            } catch (MQClientException e) {
                throw new MessageBusException("RocketMQ consumer start failed for topic " + topic, e);
            }
        });
    }

    private String consumerGroup(String logicalTopic) {
        return properties.consumerGroupPrefix() + "-" + logicalTopic;
    }

    org.apache.rocketmq.common.message.Message toRocketMessageForTest(String logicalTopic, Message msg) throws Exception {
        return toRocketMessage(logicalTopic, msg);
    }

    Message fromRocketMessageForTest(MessageExt message) throws Exception {
        return fromRocketMessage(message);
    }

    ConsumeFromWhere consumeFromWhereForTest() {
        return properties.consumeFromWhere();
    }

    ConsumeConcurrentlyStatus consumeForTest(String logicalTopic, List<MessageExt> messages) {
        return consume(logicalTopic, messages);
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
        if (msg.getConversationId() != null && !msg.getConversationId().isBlank()) {
            rocketMessage.putUserProperty("conversationId", msg.getConversationId());
        }
        if (msg.getMessageSeq() > 0) {
            rocketMessage.putUserProperty("messageSeq", String.valueOf(msg.getMessageSeq()));
        }
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
            return consume(logicalTopic, messages);
        }
    }

    private ConsumeConcurrentlyStatus consume(String logicalTopic, List<MessageExt> messages) {
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
