package com.im.infrastructure.message.rocketmq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

interface RocketMqMessageSender {

    void start() throws Exception;

    SendResult send(Message message, long timeoutMillis) throws Exception;

    void shutdown();
}
