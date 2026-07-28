package com.im.core.redis;

import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.core.session.SessionManager;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4");
        try {
            routes.online(userId, "node-a", PlatformID.WEB, "session-a");
            routes.online(userId, "node-b", "lease-b", PlatformID.WEB, "session-b");
            routes.setOnline(userId, PlatformID.WEB);

            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            assertTrue(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));

            offlineCurrent(routes, userId, "node-b", PlatformID.WEB, "session-b");
            assertFalse(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            offlineCurrent(routes, userId, "node-b", PlatformID.WEB, "session-b");
            redis.close();
        }
    }

    @Test
    void concurrentRemovalKeepsSamePlatformOnlineWhenOneBindingSurvives() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String userId = "route-race-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4");
        try {
            routes.online(userId, "node-a", PlatformID.WEB, "session-a");
            routes.online(userId, "node-b", "lease-b", PlatformID.WEB, "session-b");
            routes.online(userId, "node-c", "lease-c", PlatformID.WEB, "session-c");
            routes.setOnline(userId, PlatformID.WEB);

            concurrently(List.of(
                    () -> routes.offline(userId, "node-a", PlatformID.WEB, "session-a"),
                    () -> offlineCurrent(routes, userId, "node-b", PlatformID.WEB, "session-b")));

            assertTrue(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, "node-a", PlatformID.WEB, "session-a");
            offlineCurrent(routes, userId, "node-b", PlatformID.WEB, "session-b");
            offlineCurrent(routes, userId, "node-c", PlatformID.WEB, "session-c");
            redis.close();
        }
    }

    @Test
    void cleanupNeverDeletesNodeIndexBindingAddedAfterItsSnapshot() throws Exception {
        RedisConfiguration cleanupRedis = redisOrSkip();
        RedisConfiguration bindingRedis = redisOrSkip();
        String nodeId = "route-cleanup-node-" + UUID.randomUUID();
        String nodeIncarnation = "route-cleanup-incarnation-" + UUID.randomUUID();
        RedisRouteTable cleanupRoutes = new RedisRouteTable(
                cleanupRedis, new SessionManager(), "cleanup-client", "tagged-v4");
        RedisRouteTable bindingRoutes = new RedisRouteTable(
                bindingRedis, new SessionManager(), "binding-client", "tagged-v4");
        int seededBindings = 80;
        String newUserId = "route-new-user-" + UUID.randomUUID();
        try {
            for (int index = 0; index < seededBindings; index++) {
                cleanupRoutes.online("route-old-user-" + UUID.randomUUID(), nodeId, nodeIncarnation,
                        PlatformID.WEB, "old-" + index);
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> cleanup = executor.submit(
                        () -> cleanupRoutes.cleanupNodeRoutes(nodeId, nodeIncarnation));
                awaitNodeIndexShrink(bindingRedis, nodeId, seededBindings);
                bindingRoutes.online(newUserId, nodeId, nodeIncarnation, PlatformID.WEB, "new-session");
                assertEquals(seededBindings, cleanup.get(20, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }

            assertTrue(nodeIndexContainsPrefix(bindingRedis, nodeId,
                    newUserId + "|" + PlatformID.WEB + ":new-session|"));
            assertTrue(bindingRoutes.lookupAllBindings(newUserId).stream()
                    .anyMatch(binding -> "new-session".equals(binding.sessionId())));
        } finally {
            offlineCurrent(bindingRoutes, newUserId, nodeId, PlatformID.WEB, "new-session");
            cleanupRedis.close();
            bindingRedis.close();
        }
    }

    @Test
    void realCleanupSnapshotCannotRemoveBindingReregisteredBeforeStaleRenew() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-generation-node-" + UUID.randomUUID();
        String nodeIncarnation = "route-generation-incarnation-" + UUID.randomUUID();
        String userId = "route-generation-user-" + UUID.randomUUID();
        String field = PlatformID.WEB + ":same-session";
        RedisRouteTable routes = new RedisRouteTable(
                redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");
        try {
            routes.online(userId, nodeId, PlatformID.WEB, "same-session");
            String indexKey = "im:route-node:v4:" + nodeId;
            java.util.Set<String> cleanupSnapshot = nodeIndexMembers(redis, indexKey);
            String staleRouteValue = routeValue(redis, userId, field);

            routes.online(userId, nodeId, PlatformID.WEB, "same-session");
            assertFalse(routes.renewRouteBinding(userId, PlatformID.WEB, "same-session", staleRouteValue));

            assertEquals(0, routes.cleanupNodeRoutes(nodeId, nodeIncarnation, cleanupSnapshot));
            assertTrue(routes.lookupAllBindings(userId).stream()
                    .anyMatch(binding -> "same-session".equals(binding.sessionId())));
        } finally {
            routes.offline(userId, nodeId, PlatformID.WEB, "same-session");
            redis.close();
        }
    }

    @Test
    void staleDeliverySnapshotCannotRemoveBindingRenewedAfterLookup() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-stale-delivery-node-" + UUID.randomUUID();
        String nodeIncarnation = "route-stale-delivery-incarnation-" + UUID.randomUUID();
        String userId = "route-stale-delivery-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(
                redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");
        try {
            routes.online(userId, nodeId, PlatformID.WEB, "delivery-session");
            routes.setOnline(userId, PlatformID.WEB);
            RouteBinding stale = routes.lookupAllBindings(userId).getFirst();

            routes.renewOnline(userId, PlatformID.WEB, "delivery-session");
            assertFalse(routes.offlineIfCurrent(stale));

            assertTrue(routes.lookupAllBindings(userId).stream()
                    .anyMatch(binding -> "delivery-session".equals(binding.sessionId())));
            assertTrue(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, nodeId, PlatformID.WEB, "delivery-session");
            redis.close();
        }
    }

    @Test
    void restartRebuildsCurrentReverseIndexAfterCrossSlotRegistrationFailure() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-recovery-node-" + UUID.randomUUID();
        String nodeIncarnation = "route-recovery-incarnation-" + UUID.randomUUID();
        String userId = "route-recovery-user-" + UUID.randomUUID();
        String memberPrefix = userId + "|" + PlatformID.WEB + ":recovery-session|";
        RedisRouteTable routes = new RedisRouteTable(
                redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");
        try {
            routes.online(userId, nodeId, PlatformID.WEB, "recovery-session");
            removeNodeIndexMembersWithPrefix(redis, nodeId, memberPrefix);
            assertFalse(nodeIndexContainsPrefix(redis, nodeId, memberPrefix));

            new RedisRouteTable(redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");

            assertTrue(nodeIndexContainsPrefix(redis, nodeId, memberPrefix));
            assertTrue(routes.lookupAllBindings(userId).stream()
                    .anyMatch(binding -> "recovery-session".equals(binding.sessionId())));
        } finally {
            routes.offline(userId, nodeId, PlatformID.WEB, "recovery-session");
            redis.close();
        }
    }

    @Test
    void cleanupFindsCurrentRouteWhenCrossSlotReverseIndexWriteWasLost() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-cleanup-recovery-node-" + UUID.randomUUID();
        String nodeIncarnation = "route-cleanup-recovery-incarnation-" + UUID.randomUUID();
        String userId = "route-cleanup-recovery-user-" + UUID.randomUUID();
        String memberPrefix = userId + "|" + PlatformID.WEB + ":recovery-session|";
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), "cleanup-client", "tagged-v4");
        try {
            routes.online(userId, nodeId, nodeIncarnation, PlatformID.WEB, "recovery-session");
            removeNodeIndexMembersWithPrefix(redis, nodeId, memberPrefix);

            assertEquals(1, routes.cleanupNodeRoutes(nodeId, nodeIncarnation));
            assertTrue(routes.lookupAllBindings(userId).isEmpty());
        } finally {
            routes.offline(userId, nodeId, PlatformID.WEB, "recovery-session");
            redis.close();
        }
    }

    @Test
    void clusterScanReconcilesAndCleansRoutesFromEveryMaster() {
        RedisConfiguration redis = redisOrSkip();
        try {
            Assumptions.assumeTrue(redis.isClusterMode(),
                    "multi-master route scan test requires IM_E2E_REDIS_CLUSTER_NODES");
            String nodeId = "route-all-masters-node-" + UUID.randomUUID();
            String nodeIncarnation = "route-all-masters-incarnation-" + UUID.randomUUID();
            List<String> userIds = userIdsOnEveryClusterMaster(redis);
            RedisRouteTable routes = new RedisRouteTable(
                    redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");
            try {
                for (String userId : userIds) {
                    routes.online(userId, nodeId, PlatformID.WEB, "all-masters-session");
                }
                removeNodeIndexMembersWithPrefix(redis, nodeId, "");

                new RedisRouteTable(redis, new SessionManager(), nodeId, nodeIncarnation, "tagged-v4");

                for (String userId : userIds) {
                    assertTrue(nodeIndexContainsPrefix(redis, nodeId,
                            userId + "|" + PlatformID.WEB + ":all-masters-session|"));
                }

                removeNodeIndexMembersWithPrefix(redis, nodeId, "");
                assertEquals(userIds.size(), routes.cleanupNodeRoutes(nodeId, nodeIncarnation));
                for (String userId : userIds) {
                    assertTrue(routes.lookupAllBindings(userId).isEmpty());
                }
            } finally {
                for (String userId : userIds) {
                    routes.offline(userId, nodeId, PlatformID.WEB, "all-masters-session");
                }
            }
        } finally {
            redis.close();
        }
    }

    @Test
    void renewAfterOfflineCannotRecreatePlatformOnlineState() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-offline-node-" + UUID.randomUUID();
        String userId = "route-offline-user-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), nodeId, "tagged-v4");
        try {
            routes.online(userId, nodeId, PlatformID.WEB, "offline-session");
            routes.setOnline(userId, PlatformID.WEB);
            routes.offline(userId, nodeId, PlatformID.WEB, "offline-session");

            routes.renewOnline(userId, PlatformID.WEB, "offline-session");

            assertTrue(routes.lookupAllBindings(userId).isEmpty());
            assertFalse(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB));
        } finally {
            routes.offline(userId, nodeId, PlatformID.WEB, "offline-session");
            redis.close();
        }
    }

    @Test
    void concurrentRenewAndOfflineConvergeToRouteAndPlatformOffline() throws Exception {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "route-renew-race-node-" + UUID.randomUUID();
        RedisRouteTable routes = new RedisRouteTable(redis, new SessionManager(), nodeId, "tagged-v4");
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                String userId = "route-renew-race-user-" + UUID.randomUUID();
                routes.online(userId, nodeId, PlatformID.WEB, "race-session");
                routes.setOnline(userId, PlatformID.WEB);

                concurrently(List.of(
                        () -> routes.renewOnline(userId, PlatformID.WEB, "race-session"),
                        () -> routes.offline(userId, nodeId, PlatformID.WEB, "race-session")));

                assertTrue(routes.lookupAllBindings(userId).isEmpty(), "route remained on attempt " + attempt);
                assertFalse(routes.getOnlinePlatforms(userId).contains(PlatformID.WEB),
                        "platform remained online on attempt " + attempt);
            }
        } finally {
            redis.close();
        }
    }

    @Test
    void taggedV4LayoutRejectsLegacyMarkersKeysFormatsAndConflictingRuntimeMarker() {
        RedisConfiguration redis = redisOrSkip();
        String legacyKey = "route:legacy-user-" + UUID.randomUUID();
        String v2Key = "im:route:v2:{u-" + UUID.randomUUID() + "}";
        String v3Key = "im:route:v3:{u-" + UUID.randomUUID() + "}";
        String malformedV4Key = "im:route:v4:{u-" + UUID.randomUUID() + "}";
        String malformedV4IndexKey = "im:route-node:v4:malformed-" + UUID.randomUUID();
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.set("im:route:key-layout", "tagged-v2");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));

            sync.del("im:route:key-layout");
            sync.hset(legacyKey, "1:legacy", "old-node|1");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
            sync.del(legacyKey);

            sync.hset(v2Key, "1:old", "old-node|1");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
            sync.del(v2Key);

            sync.hset(v3Key, "1:legacy-v3", "node-a|9999999999999|generation");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
            sync.del(v3Key);

            sync.hset(malformedV4Key, "1:no-incarnation", "node-a|9999999999999|generation");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
            sync.del(malformedV4Key);

            sync.sadd(malformedV4IndexKey, "user|5:session|generation");
            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
            sync.del(malformedV4IndexKey);

            RedisRouteTable routes = new RedisRouteTable(
                    redis, new SessionManager(), "node-a", "tagged-v4");
            sync.set("im:route:key-layout", "draining-v4");
            assertThrows(IllegalStateException.class, () ->
                    routes.online("route-marker-user-" + UUID.randomUUID(), "node-a", PlatformID.WEB, "session"));
        } finally {
            try (CloseableRedisCommands commands = redis.createSyncCommands()) {
                var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
                sync.del(legacyKey);
                sync.del(v2Key);
                sync.del(v3Key);
                sync.del(malformedV4Key);
                sync.del(malformedV4IndexKey);
                sync.del("im:route:key-layout");
            }
            redis.close();
        }
    }

    @Test
    void taggedV4ReadinessRejectsOnlineKeyWithWrongRedisType() {
        RedisConfiguration redis = redisOrSkip();
        String key = onlineKey("route-online-type-user-" + UUID.randomUUID());
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.del("im:route:key-layout");
            sync.set(key, "not-a-zset");

            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
        } finally {
            deleteReadinessFixture(redis, key);
            redis.close();
        }
    }

    @Test
    void taggedV4ReadinessRejectsMalformedOnlineKey() {
        RedisConfiguration redis = redisOrSkip();
        String key = "im:online:v4:not-tagged-" + UUID.randomUUID();
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.del("im:route:key-layout");
            sync.zadd(key, System.currentTimeMillis() + 60_000D, String.valueOf(PlatformID.WEB));

            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
        } finally {
            deleteReadinessFixture(redis, key);
            redis.close();
        }
    }

    @Test
    void taggedV4ReadinessRejectsNonnumericOnlinePlatform() {
        RedisConfiguration redis = redisOrSkip();
        String key = onlineKey("route-online-member-user-" + UUID.randomUUID());
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.del("im:route:key-layout");
            sync.zadd(key, System.currentTimeMillis() + 60_000D, "not-a-platform");

            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
        } finally {
            deleteReadinessFixture(redis, key);
            redis.close();
        }
    }

    @Test
    void taggedV4ReadinessRejectsInvalidOnlineExpiryScore() {
        RedisConfiguration redis = redisOrSkip();
        String key = onlineKey("route-online-score-user-" + UUID.randomUUID());
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.del("im:route:key-layout");
            sync.zadd(key, -1D, String.valueOf(PlatformID.WEB));

            assertThrows(IllegalStateException.class, () ->
                    new RedisRouteTable(redis, new SessionManager(), "node-a", "tagged-v4"));
        } finally {
            deleteReadinessFixture(redis, key);
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

    private static void offlineCurrent(RedisRouteTable routes, String userId, String nodeId,
                                       int platformId, String sessionId) {
        routes.lookupAllBindings(userId).stream()
                .filter(binding -> nodeId.equals(binding.nodeId()))
                .filter(binding -> platformId == binding.platformId())
                .filter(binding -> sessionId.equals(binding.sessionId()))
                .findFirst()
                .ifPresent(routes::offline);
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
                Long size = sync.scard("im:route-node:v4:" + nodeId);
                if (size != null && size < initialSize) return;
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("route cleanup did not begin within 10 seconds");
    }

    private static boolean nodeIndexContainsPrefix(RedisConfiguration redis, String nodeId, String prefix) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync()
                    .smembers("im:route-node:v4:" + nodeId).stream().anyMatch(entry -> entry.startsWith(prefix));
        }
    }

    private static java.util.Set<String> nodeIndexMembers(RedisConfiguration redis, String key) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync().smembers(key);
        }
    }

    private static String routeValue(RedisConfiguration redis, String userId, String field) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync()
                    .hget(routeKey(userId), field);
        }
    }

    private static List<String> userIdsOnEveryClusterMaster(RedisConfiguration redis) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            RedisAdvancedClusterCommands<String, String> cluster =
                    (RedisAdvancedClusterCommands<String, String>) commands.sync();
            List<RedisClusterNode> masters = cluster.getStatefulConnection().getPartitions().stream()
                    .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM) && !node.hasNoSlots())
                    .toList();
            Assumptions.assumeTrue(masters.size() > 1, "route scan test requires multiple Redis masters");

            Map<String, String> userIdByMaster = new LinkedHashMap<>();
            for (int candidate = 0; candidate < 100_000 && userIdByMaster.size() < masters.size(); candidate++) {
                String userId = "route-master-slot-user-" + candidate + "-" + UUID.randomUUID();
                int slot = SlotHash.getSlot(routeKey(userId));
                masters.stream()
                        .filter(master -> master.hasSlot(slot))
                        .findFirst()
                        .ifPresent(master -> userIdByMaster.putIfAbsent(master.getNodeId(), userId));
            }
            assertEquals(masters.size(), userIdByMaster.size(),
                    "failed to select a route hash slot on every Redis master");
            return List.copyOf(userIdByMaster.values());
        }
    }

    private static String routeKey(String userId) {
        return "im:route:v4:{u-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8)) + "}";
    }

    private static String onlineKey(String userId) {
        return "im:online:v4:{u-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8)) + "}";
    }

    private static void deleteReadinessFixture(RedisConfiguration redis, String key) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            sync.del(key);
            sync.del("im:route:key-layout");
        }
    }

    private static void removeNodeIndexMembersWithPrefix(RedisConfiguration redis, String nodeId, String prefix) {
        String key = "im:route-node:v4:" + nodeId;
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            var sync = commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync();
            for (String member : sync.smembers(key)) {
                if (member.startsWith(prefix)) sync.srem(key, member);
            }
        }
    }
}
