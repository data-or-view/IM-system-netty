package com.im.infrastructure.message.rocketmq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

final class DefaultRocketMqMessageSender implements RocketMqMessageSender {

    private final DefaultMQProducer producer;

    DefaultRocketMqMessageSender(RocketMqProducerProperties properties) {
        this.producer = new DefaultMQProducer(properties.producerGroup());
        producer.setNamesrvAddr(properties.nameServer());
        // Keep timeout/retry in config so local Docker, CI and production brokers can tune
        // network tolerance without changing producer semantics.
        producer.setSendMsgTimeout(Math.toIntExact(properties.sendTimeout().toMillis()));
        producer.setRetryTimesWhenSendFailed(properties.retryTimesWhenSendFailed());
    }

    @Override
    public void start() throws Exception {
        producer.start();
    }

    @Override
    public SendResult send(Message message, long timeoutMillis) throws Exception {
        return producer.send(message, timeoutMillis);
    }

    @Override
    public void shutdown() {
        producer.shutdown();
    }
}
