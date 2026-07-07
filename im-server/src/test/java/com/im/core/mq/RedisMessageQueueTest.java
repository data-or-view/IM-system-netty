package com.im.core.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisMessageQueueTest {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Test
    void consumerClaimsAndAcksTimedOutPendingMessages() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String topic = "test-pending-" + UUID.randomUUID();
        String streamKey = RedisMessageQueue.STREAM_PREFIX + topic;
        String groupName = RedisMessageQueue.GROUP_PREFIX + topic;
        String messageId = "msg-pending-" + UUID.randomUUID();
        RedisMessageQueue queue = null;
        try {
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
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
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
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
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
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
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
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
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
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
            try (RedisConfiguration.CloseableRedisCommands commands = redis.createSyncCommands()) {
                pending = commands.<RedisCommands<String, String>>sync().xpending(streamKey, groupName);
                if (pending.getCount() == 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        assertEquals(0, pending != null ? pending.getCount() : -1, "pending messages should be acked after recovery");
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
