package com.im.core.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageSendFailureRecord;
import com.im.api.SendMessageFailureStore;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueue;
import com.im.infrastructure.message.rocketmq.RocketMqMessageQueueProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFailureCompensatorIT {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Test
    void republishesBusinessDlqRecordThroughRealRocketMq() throws Exception {
        assumeRocketMqReachable();
        RocketMqMessageQueueProperties properties = isolatedProperties("dlq");
        RocketMqMessageQueue queue = new RocketMqMessageQueue(properties, "node-dlq-it");
        InMemoryBusinessDlqStore failureStore = new InMemoryBusinessDlqStore(payload("msg-dlq-it"));
        CountDownLatch received = new CountDownLatch(1);
        List<Message> republished = new CopyOnWriteArrayList<>();

        queue.subscribe("deliver", msg -> {
            republished.add(msg);
            received.countDown();
        });

        try {
            queue.start();
            MessageFailureCompensator compensator = new MessageFailureCompensator(
                    queue, failureStore, 10, 3, 1000, 1000, 5000);

            int replayed = compensator.replayDueFailures();

            assertEquals(1, replayed);
            assertTrue(received.await(30, TimeUnit.SECONDS), "republished business-DLQ message was not consumed");
            assertEquals("msg-dlq-it", republished.getFirst().getMessageId());
            assertEquals(List.of("PENDING", "RETRYING", "REPUBLISHED"), failureStore.transitions);
        } finally {
            queue.stop();
        }
    }

    private static String payload(String messageId) throws Exception {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId("single_alice_bob");
        message.setMessageSeq(99);
        return MAPPER.writeValueAsString(message.toJsonMap());
    }

    private static RocketMqMessageQueueProperties isolatedProperties(String testName) {
        String suffix = Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toString(ThreadLocalRandom.current().nextInt(1_000_000), 36);
        return new RocketMqMessageQueueProperties(
                nameServer(),
                "im-it-producer-" + testName + "-" + suffix,
                "im-it-consumer-" + testName + "-" + suffix,
                "im-it-" + testName + "-" + suffix + "-",
                Duration.ofSeconds(3),
                1);
    }

    private static String nameServer() {
        return System.getenv().getOrDefault("IM_ROCKETMQ_IT_NAME_SERVER", "127.0.0.1:9876");
    }

    private static void assumeRocketMqReachable() {
        String[] hostPort = nameServer().split(":", 2);
        Assumptions.assumeTrue(hostPort.length == 2, "RocketMQ name-server must use host:port");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])), 1500);
        } catch (IOException | NumberFormatException e) {
            Assumptions.abort("RocketMQ integration tests skipped because name-server is unreachable: "
                    + nameServer() + " (" + e.getMessage() + ")");
        }
    }

    private static final class InMemoryBusinessDlqStore implements SendMessageFailureStore {
        private final String payloadJson;
        private final List<String> transitions = new CopyOnWriteArrayList<>(List.of("PENDING"));
        private boolean claimed;

        private InMemoryBusinessDlqStore(String payloadJson) {
            this.payloadJson = payloadJson;
        }

        @Override
        public void recordFailure(String topic, Message message, Throwable cause) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MessageSendFailureRecord> claimDueFailures(long nowMillis, int limit, long leaseMillis) {
            if (claimed) {
                return List.of();
            }
            claimed = true;
            transitions.add("RETRYING");
            return List.of(new MessageSendFailureRecord(1L, "deliver", "msg-dlq-it", payloadJson, 0));
        }

        @Override
        public void markRepublished(long id) {
            transitions.add("REPUBLISHED");
        }
    }
}
