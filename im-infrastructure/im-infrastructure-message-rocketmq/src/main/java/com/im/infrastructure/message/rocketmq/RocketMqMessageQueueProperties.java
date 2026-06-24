package com.im.infrastructure.message.rocketmq;

import com.im.config.Config;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;

import java.time.Duration;
import java.util.Locale;

public record RocketMqMessageQueueProperties(String nameServer,
                                             String producerGroup,
                                             String consumerGroupPrefix,
                                             String topicPrefix,
                                             Duration sendTimeout,
                                             int retryTimesWhenSendFailed,
                                             ConsumeFromWhere consumeFromWhere,
                                             String consumeTimestamp) {

    private static final String ROCKETMQ_TOPIC_PATTERN = "[%|a-zA-Z0-9_-]*";

    public static final String KEY_NAME_SERVER = "im.rocketmq.name-server";
    public static final String KEY_PRODUCER_GROUP = "im.rocketmq.producer.group";
    public static final String KEY_CONSUMER_GROUP_PREFIX = "im.rocketmq.consumer.group-prefix";
    public static final String KEY_TOPIC_PREFIX = "im.rocketmq.topic-prefix";
    public static final String KEY_SEND_TIMEOUT_MS = "im.rocketmq.send.timeout-ms";
    public static final String KEY_RETRY_TIMES = "im.rocketmq.retry-times";
    public static final String KEY_CONSUME_FROM_WHERE = "im.rocketmq.consumer.consume-from-where";
    public static final String KEY_CONSUME_TIMESTAMP = "im.rocketmq.consumer.consume-timestamp";

    private static final String DEFAULT_PRODUCER_GROUP = "im-producer";
    private static final String DEFAULT_CONSUMER_GROUP_PREFIX = "im-consumer";
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_RETRY_TIMES = 2;
    private static final ConsumeFromWhere DEFAULT_CONSUME_FROM_WHERE = ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET;
    private static final String DEFAULT_CONSUME_TIMESTAMP = "";

    public RocketMqMessageQueueProperties(String nameServer,
                                          String producerGroup,
                                          String consumerGroupPrefix,
                                          String topicPrefix,
                                          Duration sendTimeout,
                                          int retryTimesWhenSendFailed) {
        this(nameServer, producerGroup, consumerGroupPrefix, topicPrefix, sendTimeout, retryTimesWhenSendFailed,
                DEFAULT_CONSUME_FROM_WHERE, DEFAULT_CONSUME_TIMESTAMP);
    }

    public RocketMqMessageQueueProperties(String nameServer,
                                          String producerGroup,
                                          String consumerGroupPrefix,
                                          String topicPrefix,
                                          Duration sendTimeout,
                                          int retryTimesWhenSendFailed,
                                          ConsumeFromWhere consumeFromWhere) {
        this(nameServer, producerGroup, consumerGroupPrefix, topicPrefix, sendTimeout, retryTimesWhenSendFailed,
                consumeFromWhere, DEFAULT_CONSUME_TIMESTAMP);
    }

    public RocketMqMessageQueueProperties {
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalArgumentException("RocketMQ nameServer must not be blank");
        }
        if (producerGroup == null || producerGroup.isBlank()) {
            throw new IllegalArgumentException("RocketMQ producerGroup must not be blank");
        }
        if (consumerGroupPrefix == null || consumerGroupPrefix.isBlank()) {
            throw new IllegalArgumentException("RocketMQ consumerGroupPrefix must not be blank");
        }
        if (topicPrefix == null) {
            topicPrefix = "";
        }
        if (!topicPrefix.matches(ROCKETMQ_TOPIC_PATTERN)) {
            throw new IllegalArgumentException(
                    "RocketMQ topicPrefix contains illegal characters; only % | letters digits _ and - are allowed");
        }
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("RocketMQ sendTimeout must be positive");
        }
        if (retryTimesWhenSendFailed < 0) {
            throw new IllegalArgumentException("RocketMQ retryTimesWhenSendFailed must not be negative");
        }
        if (consumeFromWhere == null) {
            consumeFromWhere = DEFAULT_CONSUME_FROM_WHERE;
        }
        if (consumeTimestamp == null) {
            consumeTimestamp = DEFAULT_CONSUME_TIMESTAMP;
        }
        if (consumeFromWhere == ConsumeFromWhere.CONSUME_FROM_TIMESTAMP
                && !consumeTimestamp.isBlank()
                && !consumeTimestamp.matches("\\d{14}")) {
            throw new IllegalArgumentException("RocketMQ consumeTimestamp must use yyyyMMddHHmmss when configured");
        }
    }

    public static RocketMqMessageQueueProperties from(Config config) {
        String nameServer = config.getRequiredString(KEY_NAME_SERVER);
        String producerGroup = config.getString(KEY_PRODUCER_GROUP, DEFAULT_PRODUCER_GROUP);
        String consumerGroupPrefix = config.getString(KEY_CONSUMER_GROUP_PREFIX, DEFAULT_CONSUMER_GROUP_PREFIX);
        String topicPrefix = config.getString(KEY_TOPIC_PREFIX, "");
        Duration sendTimeout = config.getLong(KEY_SEND_TIMEOUT_MS)
                .map(Duration::ofMillis)
                .orElse(DEFAULT_SEND_TIMEOUT);
        int retries = config.getInt(KEY_RETRY_TIMES, DEFAULT_RETRY_TIMES);
        ConsumeFromWhere consumeFromWhere = config.getString(KEY_CONSUME_FROM_WHERE)
                .map(RocketMqMessageQueueProperties::parseConsumeFromWhere)
                .orElse(DEFAULT_CONSUME_FROM_WHERE);
        String consumeTimestamp = config.getString(KEY_CONSUME_TIMESTAMP, DEFAULT_CONSUME_TIMESTAMP);
        return new RocketMqMessageQueueProperties(
                nameServer, producerGroup, consumerGroupPrefix, topicPrefix, sendTimeout, retries,
                consumeFromWhere, consumeTimestamp);
    }

    private static ConsumeFromWhere parseConsumeFromWhere(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CONSUME_FROM_WHERE;
        }
        try {
            return ConsumeFromWhere.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported RocketMQ consume-from-where: " + value, e);
        }
    }
}
