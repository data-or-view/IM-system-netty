package com.im.infrastructure.message.rocketmq;

import com.im.config.Config;

import java.time.Duration;

public record RocketMqProducerProperties(String nameServer,
                                         String producerGroup,
                                         Duration sendTimeout,
                                         int retryTimesWhenSendFailed) {

    public static final String KEY_NAME_SERVER = "im.rocketmq.name-server";
    public static final String KEY_PRODUCER_GROUP = "im.rocketmq.producer.group";
    public static final String KEY_SEND_TIMEOUT_MS = "im.rocketmq.send.timeout-ms";
    public static final String KEY_RETRY_TIMES = "im.rocketmq.retry-times";

    private static final String DEFAULT_PRODUCER_GROUP = "im-producer";
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(3);
    private static final int DEFAULT_RETRY_TIMES = 2;

    public RocketMqProducerProperties {
        if (nameServer == null || nameServer.isBlank()) {
            throw new IllegalArgumentException("RocketMQ nameServer must not be blank");
        }
        if (producerGroup == null || producerGroup.isBlank()) {
            throw new IllegalArgumentException("RocketMQ producerGroup must not be blank");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("RocketMQ sendTimeout must be positive");
        }
        if (retryTimesWhenSendFailed < 0) {
            throw new IllegalArgumentException("RocketMQ retryTimesWhenSendFailed must not be negative");
        }
    }

    public static RocketMqProducerProperties from(Config config) {
        String nameServer = config.getRequiredString(KEY_NAME_SERVER);
        String producerGroup = config.getString(KEY_PRODUCER_GROUP, DEFAULT_PRODUCER_GROUP);
        Duration sendTimeout = config.getLong(KEY_SEND_TIMEOUT_MS)
                .map(Duration::ofMillis)
                .orElse(DEFAULT_SEND_TIMEOUT);
        int retryTimes = config.getInt(KEY_RETRY_TIMES, DEFAULT_RETRY_TIMES);
        return new RocketMqProducerProperties(nameServer, producerGroup, sendTimeout, retryTimes);
    }
}
