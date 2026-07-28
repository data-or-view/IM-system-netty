package com.im.core.redis;

import com.im.api.PlatformID;
import com.im.core.session.SessionManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRouteTableE2ETest {

    @Test
    void removingOneSamePlatformBindingKeepsPlatformOnlineUntilLastBindingLeaves() {
        RedisConfiguration redis = redisOrSkip();
        String userId = "route-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v2");
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
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v2");
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

    @Test
    void cleanupNeverDeletesNodeIndexBindingAddedAfterItsSnapshot() throws Exception {
        RedisConfiguration cleanupRedis = redisOrSkip();
        RedisConfiguration bindingRedis = redisOrSkip();
        String nodeId = "route-cleanup-node-" + UUID.randomUUID();
        RedisRouteTable cleanupRoutes = new RedisRouteTable(
                cleanupRedis, new SessionManager(), "cleanup-client", "tagged-v2");
        RedisRouteTable bindingRoutes = new RedisRouteTable(
                bindingRedis, new SessionManager(), "binding-client", "tagged-v2");
        int seededBindings = 80;
        String newUserId = "route-new-user-" + UUID.randomUUID();
        try {
            for (int index = 0; index < seededBindings; index++) {
                cleanupRoutes.online("route-old-user-" + UUID.randomUUID(), nodeId,
                        PlatformID.WEB, "old-" + index);
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> cleanup = executor.submit(() -> cleanupRoutes.cleanupNodeRoutes(nodeId));
                awaitNodeIndexShrink(bindingRedis, nodeId, seededBindings);
                bindingRoutes.online(newUserId, nodeId, PlatformID.WEB, "new-session");
                assertEquals(seededBindings, cleanup.get(20, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }

            assertTrue(nodeIndexContains(bindingRedis, nodeId,
                    newUserId + "|" + PlatformID.WEB + ":new-session"));
            assertTrue(bindingRoutes.lookupAllBindings(newUserId).stream()
                    .anyMatch(binding -> "new-session".equals(binding.sessionId())));
        } finally {
            bindingRoutes.offline(newUserId, nodeId, PlatformID.WEB, "new-session");
            cleanupRedis.close();
            bindingRedis.close();
        }
    }

    @Test
    void taggedLayoutRejectsLegacyKeysAndConflictingRuntimeMarker() {
        RedisConfiguration redis = redisOrSkip();
        String legacyKey = "route:legacy-user-" + UUID.randomUUID();
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.set("im:route:key-layout", "tagged-v2");
            sync.hset(legacyKey, "1:legacy", "old-node|1");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v2"));
            sync.del(legacyKey);

            RedisRouteTable routes = new RedisRouteTable(
                    redis, new SessionManager(), "node-a", "tagged-v2");
            sync.set("im:route:key-layout", "draining");
            assertThrows(IllegalStateException.class, () ->
                    routes.online("route-marker-user-" + UUID.randomUUID(), "node-a", PlatformID.WEB, "session"));
        } finally {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
                sync.del(legacyKey);
                sync.del("im:route:key-layout");
            }
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
                commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync().ping();
            }
            return redis;
        } catch (RuntimeException e) {
            Assumptions.abort("Redis route integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }

    private static void awaitNodeIndexShrink(RedisConfiguration redis, String nodeId, long initialSize)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            while (System.nanoTime() < deadline) {
                Long size = sync.scard("im:route-node:v2:" + nodeId);
                if (size != null && size < initialSize) return;
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("route cleanup did not begin within 10 seconds");
    }

    private static boolean nodeIndexContains(RedisConfiguration redis, String nodeId, String member) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return Boolean.TRUE.equals(commands
                    .<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync()
                    .sismember("im:route-node:v2:" + nodeId, member));
        }
    }
}
