package com.im.infrastructure.message.rocketmq;

import com.im.api.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketMqMessageQueueIT {

    @Test
    void publishesAndConsumesThroughRealBrokerWithIsolatedTopicAndGroup() throws Exception {
        RocketMqIntegrationSupport.assumeBrokerReachable();
        RocketMqMessageQueueProperties properties = RocketMqIntegrationSupport.isolatedProperties("roundtrip");
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties, "node-it-a");
        CountDownLatch received = new CountDownLatch(1);
        List<Message> messages = new CopyOnWriteArrayList<>();

        queue.subscribe("deliver", msg -> {
            messages.add(msg);
            received.countDown();
        });

        try {
            queue.start();
            queue.publish("deliver", message("msg-it-1", "single_alice_bob", 42));

            assertTrue(received.await(30, TimeUnit.SECONDS), "real RocketMQ consumer did not receive message");
            assertEquals("msg-it-1", messages.getFirst().getMessageId());
            assertEquals("single_alice_bob", messages.getFirst().getConversationId());
            assertEquals(42, messages.getFirst().getMessageSeq());
            assertEquals(properties.topicPrefix() + "deliver", queue.physicalTopic("deliver"));
        } finally {
            queue.stop();
        }
    }

    @Test
    void reconsumesWhenHandlerThrowsAgainstRealBroker() throws Exception {
        RocketMqIntegrationSupport.assumeBrokerReachable();
        RocketMqMessageQueueProperties properties = RocketMqIntegrationSupport.isolatedProperties("reconsume");
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties, "node-it-a");
        CountDownLatch success = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        queue.subscribe("persist", msg -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first delivery fails");
            }
            success.countDown();
        });

        try {
            queue.start();
            queue.publish("persist", message("msg-it-retry", "single_alice_bob", 7));

            assertTrue(success.await(90, TimeUnit.SECONDS),
                    "RocketMQ did not redeliver after handler failure");
            assertTrue(attempts.get() >= 2, "handler should be invoked at least twice");
        } finally {
            queue.stop();
        }
    }

    private static Message message(String messageId, String conversationId, long messageSeq) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setMessageSeq(messageSeq);
        message.setFromUserId("alice");
        message.setToUserId("bob");
        message.setContentType(101);
        message.setContent("{\"text\":\"hello\"}");
        return message;
    }
}
