package com.im.core.discovery;

import com.im.api.NodeInformation;
import com.im.api.PlatformID;
import com.im.core.redis.CloseableRedisCommands;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.session.SessionManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisNodeDiscoveryE2ETest {

    @Test
    void expiredLeaseCleanupCannotDeleteReplacementProcessWithSameNodeId() {
        RedisConfiguration redis = redisOrSkip();
        String nodeId = "discovery-aba-node-" + UUID.randomUUID();
        String oldIncarnation = "old-" + UUID.randomUUID();
        String newIncarnation = "new-" + UUID.randomUUID();
        String oldUser = "discovery-old-user-" + UUID.randomUUID();
        String reboundUser = "discovery-rebound-user-" + UUID.randomUUID();
        RedisRouteTable oldRoutes = new RedisRouteTable(
                redis, new SessionManager(), nodeId, oldIncarnation, "tagged-v4");
        RedisRouteTable newRoutes = new RedisRouteTable(
                redis, new SessionManager(), nodeId, newIncarnation, "tagged-v4");
        RedisNodeDiscovery oldDiscovery = new RedisNodeDiscovery(redis, oldRoutes, oldIncarnation);
        RedisNodeDiscovery newDiscovery = new RedisNodeDiscovery(redis, newRoutes, newIncarnation);
        try {
            oldDiscovery.register(new NodeInformation(nodeId, "127.0.0.1", 18081));
            oldRoutes.online(oldUser, nodeId, PlatformID.WEB, "old-session");
            oldRoutes.online(reboundUser, nodeId, PlatformID.WEB, "rebound-session");

            deleteNodeLease(redis, nodeId);
            newDiscovery.register(new NodeInformation(nodeId, "127.0.0.1", 28081));
            newRoutes.online(reboundUser, nodeId, PlatformID.WEB, "rebound-session");

            oldDiscovery.cleanupStaleNode(nodeId, oldIncarnation);

            assertEquals(28081, newDiscovery.getNode(nodeId).getPort());
            assertTrue(aliveMembers(redis).contains(nodeId + "|" + newIncarnation));
            assertTrue(oldRoutes.lookupAllBindings(oldUser).isEmpty());
            assertTrue(newRoutes.lookupAllBindings(reboundUser).stream()
                    .anyMatch(binding -> newIncarnation.equals(binding.nodeIncarnation())));
        } finally {
            oldDiscovery.cleanupStaleNode(nodeId, oldIncarnation);
            newDiscovery.unregister();
            redis.close();
        }
    }

    private static void deleteNodeLease(RedisConfiguration redis, String nodeId) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync()
                    .del("im:node:{" + nodeId + "}");
        }
    }

    private static Set<String> aliveMembers(RedisConfiguration redis) {
        try (CloseableRedisCommands commands = redis.createSyncCommands()) {
            return commands.<io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>>sync()
                    .smembers("im:nodes:alive");
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
            Assumptions.abort("Redis discovery integration test skipped because Redis is unreachable: " + e.getMessage());
            throw e;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
