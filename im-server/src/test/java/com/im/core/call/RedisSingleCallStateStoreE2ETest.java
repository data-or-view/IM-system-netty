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
        String requestKey = RedisSingleCallStateStore.requestSignalKey(
                "callee-" + roomId, "caller-" + roomId, "client-timeout-race");
        String requestOwnerKey = RedisSingleCallStateStore.requestOwnerKey(
                "callee-" + roomId, "client-timeout-race");
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
            cleanup(redis, roomId, requestKey, requestOwnerKey);
            redis.close();
        }
    }

    @Test
    void acknowledgementClearsPendingButRetainsExactRequestForConfiguredWindow() {
        RedisConfiguration redis = redisOrSkip();
        String roomId = "single-call-terminal-signal-" + UUID.randomUUID();
        String actorId = "caller-" + roomId;
        String peerUserId = "callee-" + roomId;
        String changedPeerUserId = "other-callee-" + roomId;
        String clientMsgId = "client-terminal-original";
        String requestKey = RedisSingleCallStateStore.requestSignalKey(actorId, peerUserId, clientMsgId);
        String requestOwnerKey = RedisSingleCallStateStore.requestOwnerKey(actorId, clientMsgId);
        String changedPeerRequestKey = RedisSingleCallStateStore.requestSignalKey(
                actorId, changedPeerUserId, clientMsgId);
        Duration retention = Duration.ofHours(25);
        try {
            RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis, retention);
            long now = System.currentTimeMillis();
            assertNotNull(store.createIfUsersIdle(ringing(roomId, now + 60_000L)));
            TerminalSignalIntent original = terminalIntent(
                    roomId, actorId, peerUserId, SignalingAction.CANCEL, clientMsgId);
            TerminalSignalIntent changedRoom = terminalIntent(
                    roomId + "-changed", actorId, peerUserId, SignalingAction.CANCEL, clientMsgId);
            TerminalSignalIntent changedAction = terminalIntent(
                    roomId, actorId, peerUserId, SignalingAction.HANGUP, clientMsgId);
            TerminalSignalIntent changedPayload = terminalIntent(
                    roomId, actorId, peerUserId, SignalingAction.CANCEL, clientMsgId, "changed");

            assertTrue(store.transitionTerminalSignal(original));
            assertNull(store.getByRoom(roomId));
            assertEquals(original, store.getPendingTerminalSignal(roomId));
            assertEquals(original, store.getTerminalSignalByRequest(actorId, peerUserId, clientMsgId));
            assertRetainedFor(redis, "im:single_call:{state}:pending_signal:" + roomId, retention);
            assertRetainedFor(redis, requestKey, retention);
            assertRetainedFor(redis, requestOwnerKey, retention);
            assertFalse(store.transitionTerminalSignal(changedRoom));
            assertFalse(store.transitionTerminalSignal(changedAction));
            assertFalse(store.transitionTerminalSignal(changedPayload));
            assertEquals(original, store.getPendingTerminalSignal(roomId));

            assertTrue(store.acknowledgeTerminalSignal(original));
            assertNull(store.getPendingTerminalSignal(roomId));
            TerminalSignalIntent retained = store.getTerminalSignalByRequest(actorId, peerUserId, clientMsgId);
            assertEquals(original, retained);
            assertSameMessage(original.message(), retained.message());
            assertRetainedFor(redis, requestKey, retention);
            assertRetainedFor(redis, requestOwnerKey, retention);

            assertTrue(store.transitionTerminalSignal(original));
            assertEquals(original, store.getTerminalSignalByRequest(actorId, peerUserId, clientMsgId));
            assertFalse(store.transitionTerminalSignal(changedRoom));
            assertFalse(store.transitionTerminalSignal(changedAction));
            assertFalse(store.transitionTerminalSignal(changedPayload));

            assertNotNull(store.createIfUsersIdle(new SingleCallSession(
                    roomId, actorId, changedPeerUserId, "voice", SingleCallSession.STATUS_RINGING,
                    "ws://sfu", now, 0L, now + 60_000L)));
            TerminalSignalIntent changedPeer = terminalIntent(
                    roomId, actorId, changedPeerUserId, SignalingAction.CANCEL, clientMsgId);
            assertFalse(store.transitionTerminalSignal(changedPeer));
            assertNotNull(store.getByRoom(roomId), "changed peer must not transition the recreated call");
        } finally {
            cleanup(redis, roomId, requestKey, requestOwnerKey, changedPeerRequestKey,
                    "im:single_call:{state}:user:" + changedPeerUserId);
            redis.close();
        }
    }

    @Test
    void concurrentExactTransitionsResolveOneCanonicalMessage() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String roomId = "single-call-canonical-race-" + UUID.randomUUID();
        String actorId = "caller-" + roomId;
        String peerUserId = "callee-" + roomId;
        String clientMsgId = "client-canonical-race";
        String requestKey = RedisSingleCallStateStore.requestSignalKey(actorId, peerUserId, clientMsgId);
        String requestOwnerKey = RedisSingleCallStateStore.requestOwnerKey(actorId, clientMsgId);
        try {
            RedisSingleCallStateStore store = new RedisSingleCallStateStore(redis, 3600);
            assertNotNull(store.createIfUsersIdle(ringing(roomId, System.currentTimeMillis() + 60_000L)));
            TerminalSignalIntent first = terminalIntent(
                    roomId, actorId, peerUserId, SignalingAction.CANCEL, clientMsgId,
                    null, 41L, 1_785_000_000_001L);
            TerminalSignalIntent second = terminalIntent(
                    roomId, actorId, peerUserId, SignalingAction.CANCEL, clientMsgId,
                    null, 42L, 1_785_000_000_002L);

            List<TransitionResult> results = concurrently(2, index -> {
                RedisSingleCallStateStore racingStore = new RedisSingleCallStateStore(redis, 3600);
                TerminalSignalIntent candidate = index == 0 ? first : second;
                boolean transitioned = racingStore.transitionTerminalSignal(candidate);
                TerminalSignalIntent resolved = racingStore.getTerminalSignalByRequest(
                        actorId, peerUserId, clientMsgId);
                return new TransitionResult(transitioned, resolved);
            });

            assertTrue(results.stream().allMatch(TransitionResult::transitioned));
            TerminalSignalIntent canonical = store.getTerminalSignalByRequest(actorId, peerUserId, clientMsgId);
            assertNotNull(canonical);
            assertTrue(canonical.equals(first) || canonical.equals(second));
            for (TransitionResult result : results) {
                assertEquals(canonical, result.resolved());
                assertSameMessage(canonical.message(), result.resolved().message());
            }
            assertEquals(canonical, store.getPendingTerminalSignal(roomId));
        } finally {
            cleanup(redis, roomId, requestKey, requestOwnerKey);
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
        return terminalIntent(roomId, actorId, peerUserId, action, clientMsgId, reason,
                17L, 1_785_000_000_000L);
    }

    private static TerminalSignalIntent terminalIntent(String roomId, String actorId, String peerUserId,
                                                       SignalingAction action, String clientMsgId, String reason,
                                                       long sequence, long timestamp) {
        String content = "{\"action\":\"" + action.name() + "\",\"roomId\":\"" + roomId + "\""
                + (reason != null ? ",\"reason\":\"" + reason + "\"" : "") + "}";
        Message message = Message.createSingle(actorId, peerUserId,
                ConversationIds.single(actorId, peerUserId), ContentType.SIGNAL.getId(), content, sequence);
        message.setMessageId(clientMsgId);
        message.setTimestamp(timestamp);
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

    private static void cleanup(RedisConfiguration redis, String roomId, String... requestKeys) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisClusterCommands<String, String> sync = commands.sync();
            sync.zrem("im:single_call:{state}:deadlines", roomId);
            List<String> keys = new ArrayList<>(List.of(
                    "im:single_call:{state}:room:" + roomId,
                    "im:single_call:{state}:user:caller-" + roomId,
                    "im:single_call:{state}:user:callee-" + roomId,
                    "im:single_call:{state}:pending_signal:" + roomId));
            keys.addAll(Arrays.asList(requestKeys));
            sync.del(keys.toArray(String[]::new));
        }
    }

    private static void assertRetainedFor(RedisConfiguration redis, String key, Duration retention) {
        long minimumExpected = Math.max(SEND_IDEMPOTENCY_TTL_SECONDS, retention.toSeconds()) - 5;
        assertTrue(redisTtl(redis, key) >= minimumExpected,
                "request state must remain replayable for its configured retention");
    }

    private static void assertSameMessage(Message expected, Message actual) {
        assertEquals(expected.getMessageId(), actual.getMessageId());
        assertEquals(expected.getSequenceId(), actual.getSequenceId());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getMessageSeq(), actual.getMessageSeq());
        assertTrue(Arrays.equals(expected.getBody(), actual.getBody()));
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

    private record TransitionResult(boolean transitioned, TerminalSignalIntent resolved) { }
}
