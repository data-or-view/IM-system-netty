package com.im.bootstrap;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.config.ConfigLoader;
import com.im.core.call.CallStateManager;
import com.im.core.call.RedisSingleCallStateStore;
import com.im.core.call.SingleCallSession;
import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CallStateManagerRedisHandoffE2ETest extends BaseE2ETest {

    private static RedisConfiguration redis;

    @BeforeAll
    static void setUpInfrastructure() throws Exception {
        startServer(Map.of(
                "im.call.enabled", "false",
                "im.db.schema", "none",
                "im.env", "macbook-dev",
                "im.mq.type", "redis"));
        redis = RedisComponentsFactory.requireRedisConfig(ConfigLoader.reload());
    }

    @AfterAll
    static void tearDownInfrastructure() {
        if (redis != null) {
            redis.close();
            redis = null;
        }
        stopServer();
    }

    @Test
    void stoppedCreatorHandsExpiredCallToRecoveringManagerExactlyOnce() {
        String roomId = "single-call-manager-handoff-" + UUID.randomUUID();
        String callerId = "caller-" + roomId;
        String calleeId = "callee-" + roomId;
        RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis);
        RecordingQueue queue = new RecordingQueue();
        CallStateManager creator = new CallStateManager(queue, store, 0, 60_000L, 10);
        CallStateManager recoveringManager = null;

        try {
            SingleCallSession created = creator.createRinging(
                    callerId, calleeId, "voice", roomId, "ws://sfu");
            assertNotNull(created);
            creator.shutdown();

            recoveringManager = new CallStateManager(queue,
                    new RedisSingleCallStateStore(redis), 0, 60_000L, 10);
            recoveringManager.scanExpiredCalls();

            assertEquals(List.of(callerId, calleeId),
                    queue.published.stream().map(Message::getToUserId).toList());
            assertEquals(List.of(
                            timeoutMessageId(roomId, callerId),
                            timeoutMessageId(roomId, calleeId)),
                    queue.published.stream().map(Message::getMessageId).toList());

            recoveringManager.scanExpiredCalls();
            assertEquals(2, queue.published.size(), "a second scan must not publish timeout messages again");
        } finally {
            creator.shutdown();
            if (recoveringManager != null) recoveringManager.shutdown();
            cleanupCall(roomId, callerId, calleeId);
        }
    }

    private static String timeoutMessageId(String roomId, String recipientId) {
        return UUID.nameUUIDFromBytes(("single-call-timeout:" + roomId + ':' + recipientId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void cleanupCall(String roomId, String callerId, String calleeId) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            sync.zrem("im:single_call:{state}:deadlines", roomId);
            sync.del(
                    "im:single_call:{state}:room:" + roomId,
                    "im:single_call:{state}:user:" + callerId,
                    "im:single_call:{state}:user:" + calleeId);
        }
    }

    private static final class RecordingQueue implements IMessageQueue {
        private final List<Message> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message message) { published.add(message); }
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }
}
