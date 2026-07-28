package com.im.core.call;

import com.im.core.redis.RedisConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSingleCallStateStoreE2ETest {

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
            assertTrue(store.claimExpiredRinging(now, 10).isEmpty());
            assertEquals(null, store.getActiveByUser("caller-" + roomId));
            assertEquals(null, store.getActiveByUser("callee-" + roomId));
        } finally {
            new RedisSingleCallStateStore(redis, 3600).end(roomId);
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

            List<Object> results = concurrently(2, index -> index == 0
                    ? new RedisSingleCallStateStore(redis, 3600).acceptBy(roomId, "callee-" + roomId)
                    : new RedisSingleCallStateStore(redis, 3600).claimExpiredRinging(now, 10));

            boolean accepted = results.stream().anyMatch(SingleCallSession.class::isInstance);
            boolean timedOut = results.stream().filter(List.class::isInstance)
                    .map(List.class::cast).anyMatch(result -> !result.isEmpty());
            assertTrue(accepted ^ timedOut);
            assertFalse(accepted && timedOut);
        } finally {
            new RedisSingleCallStateStore(redis, 3600).end(roomId);
            redis.close();
        }
    }

    private static SingleCallSession ringing(String roomId, long deadlineAt) {
        return new SingleCallSession(roomId, "caller-" + roomId, "callee-" + roomId, "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", deadlineAt - 10L, 0L, deadlineAt);
    }

    private static RedisConfiguration redisOrSkip() {
        try {
            return RedisConfiguration.builder()
                    .host(env("IM_E2E_REDIS_HOST", env("IM_REDIS_HOST", "127.0.0.1")))
                    .port(Integer.parseInt(env("IM_E2E_REDIS_PORT", env("IM_REDIS_PORT", "6379"))))
                    .password(env("IM_E2E_REDIS_PASSWORD", env("IM_REDIS_PASSWORD", "difyai123456")))
                    .timeout(Duration.ofSeconds(2))
                    .build();
        } catch (RuntimeException e) {
            Assumptions.abort("Redis single-call integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
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
