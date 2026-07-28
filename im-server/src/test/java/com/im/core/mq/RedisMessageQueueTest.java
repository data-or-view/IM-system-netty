package com.im.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.Consumer;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.PendingMessages;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisMessageQueueTest {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Test
    void handlerInitiatedStopRejectsNormalCompletionAndConsumerExits() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-handler-stop-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "handler-stop-node");
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch selfStopRejected = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        AtomicBoolean stopReturnedNormally = new AtomicBoolean();
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            queue.subscribe(topic, ignored -> {
                handlerEntered.countDown();
                try {
                    queue.stop();
                    stopReturnedNormally.set(true);
                } catch (IllegalStateException expected) {
                    selfStopRejected.countDown();
                } finally {
                    handlerExited.countDown();
                }
            });
            queue.start();
            queue.publish(topic, message("msg-handler-stop-" + UUID.randomUUID()));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "handler did not start");
            assertTrue(selfStopRejected.await(5, TimeUnit.SECONDS), "handler stop did not reject self-completion");
            assertTrue(handlerExited.await(5, TimeUnit.SECONDS), "handler remained blocked by self-stop");
            assertFalse(stopReturnedNormally.get(), "handler-initiated stop must not return normally");
            stopper.submit(queue::stop).get(5, TimeUnit.SECONDS);

            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                assertEquals(1, commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName).getCount(),
                        "message handled during self-stop must remain pending");
            }
        } finally {
            queue.stop();
            stopper.shutdownNow();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void handlerStopDuringExternalDrainDoesNotDeadlockExternalStopper() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-handler-stop-external-drain-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "handler-stop-external-node");
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch allowHandlerStop = new CountDownLatch(1);
        CountDownLatch selfStopRejected = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-mq-external-stopper");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> stopFuture = null;
        try {
            queue.subscribe(topic, ignored -> {
                handlerEntered.countDown();
                awaitUninterruptibly(allowHandlerStop);
                try {
                    queue.stop();
                } catch (IllegalStateException expected) {
                    selfStopRejected.countDown();
                }
                awaitUninterruptibly(allowHandlerExit);
                handlerExited.countDown();
            });
            queue.start();
            queue.publish(topic, message("msg-handler-stop-external-drain-" + UUID.randomUUID()));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "handler did not start");
            stopFuture = stopper.submit(queue::stop);
            assertFalse(stopFuture.isDone(), "external stop should wait for the in-flight handler");

            allowHandlerStop.countDown();
            assertTrue(selfStopRejected.await(5, TimeUnit.SECONDS),
                    "handler stop waited for the shutdown that needs this handler to exit");
            assertFalse(stopFuture.isDone(), "external stop must still wait for the handler exit");

            allowHandlerExit.countDown();
            assertTrue(handlerExited.await(5, TimeUnit.SECONDS), "handler did not exit");
            stopFuture.get(5, TimeUnit.SECONDS);
        } finally {
            allowHandlerStop.countDown();
            allowHandlerExit.countDown();
            if (stopFuture != null && stopFuture.isDone()) {
                queue.stop();
            }
            stopper.shutdownNow();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void partialUnsubscribeInvalidatesAcknowledgementForInFlightDelivery() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-partial-unsubscribe-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "partial-unsubscribe-node");
        CountDownLatch blockedHandlerEntered = new CountDownLatch(1);
        CountDownLatch allowBlockedHandlerExit = new CountDownLatch(1);
        CountDownLatch blockedHandlerExited = new CountDownLatch(1);
        CountDownLatch remainingHandlerReceived = new CountDownLatch(1);
        QueueMessageHandler blockedHandler = ignored -> {
            blockedHandlerEntered.countDown();
            awaitUninterruptibly(allowBlockedHandlerExit);
            blockedHandlerExited.countDown();
        };
        QueueMessageHandler remainingHandler = ignored -> remainingHandlerReceived.countDown();
        try {
            queue.subscribe(topic, blockedHandler);
            queue.subscribe(topic, remainingHandler);
            queue.start();
            queue.publish(topic, message("msg-partial-unsubscribe-" + UUID.randomUUID()));

            assertTrue(blockedHandlerEntered.await(5, TimeUnit.SECONDS), "blocked handler did not start");
            queue.unsubscribe(topic, blockedHandler);
            allowBlockedHandlerExit.countDown();
            assertTrue(blockedHandlerExited.await(5, TimeUnit.SECONDS), "blocked handler did not exit");
            assertTrue(remainingHandlerReceived.await(5, TimeUnit.SECONDS), "remaining handler did not receive delivery");

            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                assertEquals(1, commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName).getCount(),
                        "an in-flight delivery invalidated by partial unsubscribe must remain pending");
            }
        } finally {
            allowBlockedHandlerExit.countDown();
            queue.stop();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void unsubscribeThenStopWaitsForInFlightHandlerToExitBeforeReturning() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-unsubscribe-stop-waits-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "unsubscribe-stop-waits-node");
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        QueueMessageHandler handler = ignored -> {
            handlerEntered.countDown();
            awaitUninterruptibly(allowHandlerExit);
            handlerExited.countDown();
        };
        try {
            queue.subscribe(topic, handler);
            queue.start();
            queue.publish(topic, message("msg-unsubscribe-stop-waits-" + UUID.randomUUID()));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "handler did not start");
            queue.unsubscribe(topic, handler);
            stopper.submit(() -> {
                queue.stop();
                stopReturned.countDown();
            });

            assertFalse(stopReturned.await(5500, TimeUnit.MILLISECONDS),
                    "queue stop must outwait the former drain timeout for a consumer removed by unsubscribe");

            allowHandlerExit.countDown();
            assertTrue(handlerExited.await(5, TimeUnit.SECONDS), "handler did not exit after release");
            assertTrue(stopReturned.await(5, TimeUnit.SECONDS), "queue stop did not finish after handler exit");
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                assertEquals(1, commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName).getCount(),
                        "message completed after unsubscribe must remain reclaimable");
            }
        } finally {
            allowHandlerExit.countDown();
            queue.stop();
            stopper.shutdownNow();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void resubscribeStartsReplacementWithoutLosingItWhenRetiredConsumerExits() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-resubscribe-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "resubscribe-node");
        CountDownLatch retiredHandlerEntered = new CountDownLatch(1);
        CountDownLatch allowRetiredHandlerExit = new CountDownLatch(1);
        CountDownLatch retiredHandlerExited = new CountDownLatch(1);
        CountDownLatch replacementReceived = new CountDownLatch(2);
        CopyOnWriteArrayList<String> replacementMessageIds = new CopyOnWriteArrayList<>();
        QueueMessageHandler retiredHandler = ignored -> {
            retiredHandlerEntered.countDown();
            awaitUninterruptibly(allowRetiredHandlerExit);
            retiredHandlerExited.countDown();
        };
        QueueMessageHandler replacementHandler = message -> {
            replacementMessageIds.add(message.getMessageId());
            replacementReceived.countDown();
        };
        try {
            queue.subscribe(topic, retiredHandler);
            queue.start();
            queue.publish(topic, message("msg-retired-" + UUID.randomUUID()));
            assertTrue(retiredHandlerEntered.await(5, TimeUnit.SECONDS), "retired handler did not start");

            queue.unsubscribe(topic, retiredHandler);
            queue.subscribe(topic, replacementHandler);
            String firstReplacementId = "msg-replacement-first-" + UUID.randomUUID();
            queue.publish(topic, message(firstReplacementId));
            waitUntilSize(replacementMessageIds, 1);

            allowRetiredHandlerExit.countDown();
            assertTrue(retiredHandlerExited.await(5, TimeUnit.SECONDS), "retired handler did not exit");

            String secondReplacementId = "msg-replacement-second-" + UUID.randomUUID();
            queue.publish(topic, message(secondReplacementId));
            assertTrue(replacementReceived.await(5, TimeUnit.SECONDS),
                    "replacement consumer stopped when the retired consumer exited");
            assertEquals(List.of(firstReplacementId, secondReplacementId), replacementMessageIds);
        } finally {
            allowRetiredHandlerExit.countDown();
            queue.stop();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void stopWaitsForInFlightHandlerToExitBeforeReturning() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-stop-waits-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        RedisMessageQueue queue = new RedisMessageQueue(redis, "stop-waits-node");
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            queue.subscribe(topic, ignored -> {
                handlerEntered.countDown();
                awaitUninterruptibly(allowHandlerExit);
                handlerExited.countDown();
            });
            queue.start();
            queue.publish(topic, message("msg-stop-waits-" + UUID.randomUUID()));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "handler did not start");
            stopper.submit(() -> {
                queue.stop();
                stopReturned.countDown();
            });

            assertFalse(stopReturned.await(300, TimeUnit.MILLISECONDS),
                    "queue stop must wait for the in-flight handler before returning");

            allowHandlerExit.countDown();
            assertTrue(handlerExited.await(5, TimeUnit.SECONDS), "handler did not exit after release");
            assertTrue(stopReturned.await(5, TimeUnit.SECONDS), "queue stop did not finish after handler exit");
        } finally {
            allowHandlerExit.countDown();
            queue.stop();
            stopper.shutdownNow();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void shutdownReleasedHandlerLeavesMessagePendingForAnotherConsumerToReclaim() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-shutdown-pending-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        String messageId = "msg-shutdown-pending-" + UUID.randomUUID();
        RedisMessageQueue firstQueue = new RedisMessageQueue(redis, "shutdown-pending-first");
        RedisMessageQueue secondQueue = null;
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch shutdownInterruptedHandler = new CountDownLatch(1);
        CountDownLatch allowHandlerExit = new CountDownLatch(1);
        ExecutorService stopper = Executors.newSingleThreadExecutor();
        try {
            firstQueue.subscribe(topic, ignored -> {
                handlerEntered.countDown();
                boolean interrupted = false;
                try {
                    while (true) {
                        try {
                            allowHandlerExit.await();
                            return;
                        } catch (InterruptedException ignoredInterrupt) {
                            interrupted = true;
                            shutdownInterruptedHandler.countDown();
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            firstQueue.start();
            firstQueue.publish(topic, message(messageId));

            assertTrue(handlerEntered.await(5, TimeUnit.SECONDS), "first handler did not start");
            Future<?> stopFuture = stopper.submit(firstQueue::stop);
            assertFalse(stopFuture.isDone(), "stop should wait while the handler is blocked");
            assertTrue(shutdownInterruptedHandler.await(5, TimeUnit.SECONDS),
                    "shutdown did not interrupt the blocked first handler");

            allowHandlerExit.countDown();
            stopFuture.get(5, TimeUnit.SECONDS);

            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                assertEquals(1, commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName).getCount(),
                        "message released after shutdown began must remain pending");
            }

            CountDownLatch reclaimed = new CountDownLatch(1);
            AtomicReference<Message> reclaimedMessage = new AtomicReference<>();
            Thread.sleep(100);
            secondQueue = new RedisMessageQueue(redis, "shutdown-pending-second", Duration.ofMillis(50), 10);
            secondQueue.subscribe(topic, recovered -> {
                reclaimedMessage.set(recovered);
                reclaimed.countDown();
            });
            secondQueue.start();

            assertTrue(reclaimed.await(5, TimeUnit.SECONDS), "second consumer did not reclaim the pending message");
            assertEquals(messageId, reclaimedMessage.get().getMessageId());
            waitUntilNoPending(redis, streamKey, groupName);
        } finally {
            allowHandlerExit.countDown();
            firstQueue.stop();
            if (secondQueue != null) {
                secondQueue.stop();
            }
            stopper.shutdownNow();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void consumerClaimsAndAcksTimedOutPendingMessages() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-pending-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        String messageId = "msg-pending-" + UUID.randomUUID();
        RedisMessageQueue queue = null;
        try {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                RedisCommands<String, String> sync = commands.sync();
                sync.del(streamKey);
                sync.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0-0"), groupName,
                        XGroupCreateArgs.Builder.mkstream(true));

                Message original = message(messageId);
                sync.xadd(streamKey, "payload", MAPPER.writeValueAsString(original.toJsonMap()));

                List<io.lettuce.core.StreamMessage<String, String>> readByDeadConsumer = sync.xreadgroup(
                        Consumer.from(groupName, "dead-consumer"),
                        XReadArgs.Builder.count(1),
                        XReadArgs.StreamOffset.lastConsumed(streamKey));
                assertEquals(1, readByDeadConsumer.size(), "test setup must create one pending message");
                assertEquals(1, sync.xpending(streamKey, groupName).getCount(), "message should be pending before recovery");
            }

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Message> recovered = new AtomicReference<>();
            Thread.sleep(100);
            queue = new RedisMessageQueue(redis, "reclaim-node", Duration.ofMillis(50), 10);
            queue.subscribe(topic, msg -> {
                recovered.set(msg);
                received.countDown();
            });
            queue.start();

            assertTrue(received.await(5, TimeUnit.SECONDS), "pending message was not reclaimed");
            assertEquals(messageId, recovered.get().getMessageId());
            waitUntilNoPending(redis, streamKey, groupName);
        } finally {
            if (queue != null) {
                queue.stop();
            }
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    @Test
    void consumerClaimsPendingMessagesAcrossMultipleBatches() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-pending-batches-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        List<String> messageIds = List.of(
                "msg-pending-" + UUID.randomUUID(),
                "msg-pending-" + UUID.randomUUID(),
                "msg-pending-" + UUID.randomUUID());
        RedisMessageQueue queue = null;
        try {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                RedisCommands<String, String> sync = commands.sync();
                sync.del(streamKey);
                sync.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0-0"), groupName,
                        XGroupCreateArgs.Builder.mkstream(true));
                for (String messageId : messageIds) {
                    sync.xadd(streamKey, "payload", MAPPER.writeValueAsString(message(messageId).toJsonMap()));
                }
                List<io.lettuce.core.StreamMessage<String, String>> pendingSetup = sync.xreadgroup(
                        Consumer.from(groupName, "dead-consumer"),
                        XReadArgs.Builder.count(messageIds.size()),
                        XReadArgs.StreamOffset.lastConsumed(streamKey));
                assertEquals(messageIds.size(), pendingSetup.size(), "test setup must create pending messages");
                assertEquals(messageIds.size(), sync.xpending(streamKey, groupName).getCount());
            }

            CountDownLatch received = new CountDownLatch(messageIds.size());
            CopyOnWriteArrayList<String> recoveredIds = new CopyOnWriteArrayList<>();
            Thread.sleep(100);
            queue = new RedisMessageQueue(redis, "reclaim-node", Duration.ofMillis(50), 1);
            queue.subscribe(topic, msg -> {
                recoveredIds.add(msg.getMessageId());
                received.countDown();
            });
            queue.start();

            assertTrue(received.await(5, TimeUnit.SECONDS), "pending messages were not reclaimed across batches");
            assertEquals(messageIds, recoveredIds);
            waitUntilNoPending(redis, streamKey, groupName);
        } finally {
            if (queue != null) {
                queue.stop();
            }
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().del(streamKey);
            } finally {
                redis.close();
            }
        }
    }

    private static RedisConfiguration redisOrSkip() {
        try {
            RedisConfiguration redis = RedisConfiguration.builder()
                    .host(env("IM_E2E_REDIS_HOST", env("IM_REDIS_HOST", "127.0.0.1")))
                    .port(Integer.parseInt(env("IM_E2E_REDIS_PORT", env("IM_REDIS_PORT", "6379"))))
                    .username(env("IM_E2E_REDIS_USERNAME", env("IM_REDIS_USERNAME", "")))
                    .password(env("IM_E2E_REDIS_PASSWORD", env("IM_REDIS_PASSWORD", "difyai123456")))
                    .database(Integer.parseInt(env("IM_E2E_REDIS_DATABASE", env("IM_REDIS_DATABASE", "0"))))
                    .timeout(Duration.ofSeconds(2))
                    .build();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisCommands<String, String>>sync().ping();
            }
            return redis;
        } catch (RuntimeException e) {
            Assumptions.abort("Redis message queue integration test skipped because Redis is unreachable: "
                    + e.getMessage());
            throw e;
        }
    }

    private static void waitUntilNoPending(RedisConfiguration redis, String streamKey, String groupName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        PendingMessages pending = null;
        while (System.nanoTime() <= deadline) {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                pending = commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName);
                if (pending.getCount() == 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        assertEquals(0, pending != null ? pending.getCount() : -1, "pending messages should be acked after recovery");
    }

    private static void waitUntilSize(List<?> values, int expectedSize) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() <= deadline) {
            if (values.size() >= expectedSize) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expectedSize, values.size(), "list did not reach expected size");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Message message(String messageId) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId("single_a_b");
        message.setFromUserId("a");
        message.setToUserId("b");
        message.setContentType(101);
        message.setContent("{\"text\":\"pending\"}");
        message.setMessageSeq(1);
        return message;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
