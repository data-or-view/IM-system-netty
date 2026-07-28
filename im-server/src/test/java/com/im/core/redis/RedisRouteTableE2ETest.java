package com.im.core.redis;

import com.im.api.PlatformID;
import com.im.core.session.SessionManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRouteTableE2ETest {

    @Test
    void removingOneSamePlatformBindingKeepsPlatformOnlineUntilLastBindingLeaves() {
        RedisConfiguration redis = redisOrSkip();
        String userId = "route-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a");
        try {
            routes.online(userId, "node-a", PlatformID.WEB, "session-a");
            routes.online(userId, "node-b", PlatformID.WEB, "session-b");
            routes.setOnline(userId, PlatformID.WEB);

            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            assertTrue(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));

            routes.offline(userId, "node-b", PlatformID.WEB, "session-b");
            assertFalse(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            routes.offline(userId, "node-b", PlatformID.WEB, "session-b");
            redis.close();
        }
    }

    @Test
    void concurrentRemovalKeepsSamePlatformOnlineWhenOneBindingSurvives() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String userId = "route-race-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a");
        try {
            routes.online(userId, "node-a", PlatformID.WEB, "session-a");
            routes.online(userId, "node-b", PlatformID.WEB, "session-b");
            routes.online(userId, "node-c", PlatformID.WEB, "session-c");
            routes.setOnline(userId, PlatformID.WEB);

            concurrently(List.of(
                    () -> routes.offline(userId, "node-a", PlatformID.WEB, "session-a"),
                    () -> routes.offline(userId, "node-b", PlatformID.WEB, "session-b")));

            assertTrue(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            routes.offline(userId, "node-b", PlatformID.WEB, "session-b");
            routes.offline(userId, "node-c", PlatformID.WEB, "session-c");
            redis.close();
        }
    }

    private static void concurrently(List<Runnable> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while waiting to remove route", e);
                    }
                    action.run();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
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
            Assumptions.abort("Redis route integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
