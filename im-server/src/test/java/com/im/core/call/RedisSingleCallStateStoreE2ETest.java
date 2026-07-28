package com.im.core.call;

import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.CloseableRedisCommands;
import com.im.api.ConversationIds;
import com.im.api.Message;
import com.im.api.SignalingAction;
import com.im.api.content.ContentType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSingleCallStateStoreE2ETest {

    private static final long SEND_IDEMPOTENCY_TTL_SECONDS = 24 * 60 * 60L;

    @Test
    void exactlyOneScannerClaimsExpiredRingingCallAndFreesBothUsers() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String roomId = "single-call-deadline-" + UUID.randomUUID();
        try {
            RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis, 3600);
            long now = System.currentTimeMillis();
            assertNotNull(store.createIfUsersIdle(ringing(roomId, now - 1_000L)));

            List<List<SingleCallSession>> claims = concurrently(2, ignored ->
                    new RedisSingleCallStateStore(redis, 3600).claimExpiredRinging(now, 10));

            assertEquals(1, claims.stream().mapToInt(List::size).sum());
            SingleCallSession claimed = claims.stream().flatMap(List::stream).findFirst().orElseThrow();
            assertEquals(roomId, claimed.roomId());
            assertEquals("caller-" + roomId, claimed.callerId());
            assertEquals("callee-" + roomId, claimed.calleeId());
            assertEquals("voice", claimed.callType());
            assertEquals("ws://sfu", claimed.sfuEndpoint());
            assertEquals(SingleCallSession.STATUS_TIMED_OUT, claimed.status());
            assertTrue(store.claimExpiredRinging(now, 10).isEmpty());
            assertNull(store.getActiveByUser("caller-" + roomId));
            assertNull(store.getActiveByUser("callee-" + roomId));
            assertNull(store.endBy(roomId, "caller-" + roomId));
            assertEquals(SingleCallSession.STATUS_TIMED_OUT, store.getByRoom(roomId).status());
        } finally {
            cleanup(redis, roomId);
            redis.close();
        }
    }

    @Test
    void acceptAndDeadlineClaimHaveOneTerminalWinner() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String roomId = "single-call-race-" + UUID.randomUUID();
        try {
            RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis, 3600);
            long now = System.currentTimeMillis();
            assertNotNull(store.createIfUsersIdle(ringing(roomId, now - 1_000L)));
            TerminalSignalIntent acceptIntent = terminalIntent(
                    roomId, "callee-" + roomId, "caller-" + roomId,
                    SignalingAction.ACCEPT, "client-timeout-race");

            List<Object> results = concurrently(2, index -> index == 0
                    ? new RedisSingleCallStateStore(redis, 3600).transitionTerminalSignal(acceptIntent)
                    : new RedisSingleCallStateStore(redis, 3600).claimExpiredRinging(now, 10));

            boolean accepted = Boolean.TRUE.equals(results.get(0));
            boolean timedOut = results.stream().filter(List.class::isInstance)
                    .map(List.class::cast).anyMatch(result -> !result.isEmpty());
            assertTrue(accepted ^ timedOut);
            assertFalse(accepted && timedOut);
            assertEquals(accepted ? acceptIntent : null, store.getPendingTerminalSignal(roomId));
        } finally {
            cleanup(redis, roomId);
            redis.close();
        }
    }

    @Test
    void terminalTransitionReplaysOnlyExactPendingIntentUntilAcknowledged() {
        RedisConfiguration redis = redisOrSkip();
        String roomId = "single-call-terminal-signal-" + UUID.randomUUID();
        try {
            RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis, 3600);
            long now = System.currentTimeMillis();
            assertNotNull(store.createIfUsersIdle(ringing(roomId, now + 60_000L)));
            TerminalSignalIntent original = terminalIntent(
                    roomId, "caller-" + roomId, "callee-" + roomId,
                    SignalingAction.CANCEL, "client-terminal-original");
            TerminalSignalIntent different = terminalIntent(
                    roomId, "caller-" + roomId, "callee-" + roomId,
                    SignalingAction.HANGUP, "client-terminal-different");
            TerminalSignalIntent differentClientRequest = terminalIntent(
                    roomId, "caller-" + roomId, "callee-" + roomId,
                    SignalingAction.CANCEL, "client-terminal-different");
            TerminalSignalIntent changedPayload = terminalIntent(
                    roomId, "caller-" + roomId, "callee-" + roomId,
                    SignalingAction.CANCEL, "client-terminal-original", "changed");

            assertTrue(store.transitionTerminalSignal(original));
            assertNull(store.getByRoom(roomId));
            assertEquals(original, store.getPendingTerminalSignal(roomId));
            assertTrue(redisTtl(redis, "im:single_call:{state}:pending_signal:" + roomId)
                            >= SEND_IDEMPOTENCY_TTL_SECONDS - 5,
                    "pending signal must remain replayable for the send-idempotency window");
            TerminalSignalIntent loaded = store.getPendingTerminalSignal(roomId);
            assertNotNull(loaded.message());
            assertTrue(store.transitionTerminalSignal(loaded));
            assertFalse(store.transitionTerminalSignal(different));
            assertFalse(store.transitionTerminalSignal(differentClientRequest));
            assertFalse(store.transitionTerminalSignal(changedPayload));
            assertFalse(store.acknowledgeTerminalSignal(different));
            assertEquals(original, store.getPendingTerminalSignal(roomId));

            assertTrue(store.acknowledgeTerminalSignal(original));
            assertNull(store.getPendingTerminalSignal(roomId));
            assertFalse(store.transitionTerminalSignal(original));
        } finally {
            cleanup(redis, roomId);
            redis.close();
        }
    }

    private static SingleCallSession ringing(String roomId, long deadlineAt) {
        return new SingleCallSession(roomId, "caller-" + roomId, "callee-" + roomId, "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", deadlineAt - 10L, 0L, deadlineAt);
    }

    private static TerminalSignalIntent terminalIntent(String roomId, String actorId, String peerUserId,
                                                       SignalingAction action, String clientMsgId) {
        return terminalIntent(roomId, actorId, peerUserId, action, clientMsgId, null);
    }

    private static TerminalSignalIntent terminalIntent(String roomId, String actorId, String peerUserId,
                                                       SignalingAction action, String clientMsgId, String reason) {
        String content = "{\"action\":\"" + action.name() + "\",\"roomId\":\"" + roomId + "\""
                + (reason != null ? ",\"reason\":\"" + reason + "\"" : "") + "}";
        Message message = Message.createSingle(actorId, peerUserId,
                ConversationIds.single(actorId, peerUserId), ContentType.SIGNAL.getId(), content, 17L);
        message.setMessageId(clientMsgId);
        message.setTimestamp(1_785_000_000_000L);
        message.setBody(content.getBytes(StandardCharsets.UTF_8));
        return TerminalSignalIntent.withMessage(
                roomId, actorId, peerUserId, action, clientMsgId, message);
    }

    private static RedisConfiguration redisOrSkip() {
        try {
            RedisConfiguration.Builder builder = RedisConfiguration.builder()
                    .username(env("IM_E2E_REDIS_USERNAME", env("IM_REDIS_USERNAME", "")))
                    .password(env("IM_E2E_REDIS_PASSWORD", env("IM_REDIS_PASSWORD", "difyai123456")))
                    .timeout(Duration.ofSeconds(2));
            String clusterNodes = env("IM_E2E_REDIS_CLUSTER_NODES", "").trim();
            if (clusterNodes.isEmpty()) {
                builder.host(env("IM_E2E_REDIS_HOST", env("IM_REDIS_HOST", "127.0.0.1")))
                        .port(Integer.parseInt(env("IM_E2E_REDIS_PORT", env("IM_REDIS_PORT", "6379"))))
                        .database(Integer.parseInt(env("IM_E2E_REDIS_DATABASE", env("IM_REDIS_DATABASE", "0"))));
            } else {
                builder.clusterNodes(Arrays.stream(clusterNodes.split(","))
                        .map(String::trim)
                        .filter(node -> !node.isEmpty())
                        .toList());
            }
            RedisConfiguration redis = builder.build();
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                commands.<RedisClusterCommands<String, String>>sync().ping();
            }
            return redis;
        } catch (RuntimeException e) {
            Assumptions.abort("Redis single-call integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }

    private static void cleanup(RedisConfiguration redis, String roomId) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            sync.zrem("im:single_call:{state}:deadlines", roomId);
            sync.del(
                    "im:single_call:{state}:room:" + roomId,
                    "im:single_call:{state}:user:caller-" + roomId,
                    "im:single_call:{state}:user:callee-" + roomId,
                    "im:single_call:{state}:pending_signal:" + roomId);
        }
    }

    private static long redisTtl(RedisConfiguration redis, String key) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<RedisClusterCommands<String, String>>sync().ttl(key);
        }
    }

    private static <T> List<T> concurrently(int count, IndexedCallable<T> callable) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return callable.call(current);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedCallable<T> {
        T call(int index) throws Exception;
    }
}
