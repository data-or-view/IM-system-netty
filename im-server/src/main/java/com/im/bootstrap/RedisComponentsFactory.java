package com.im.bootstrap;

import com.im.api.INodeDiscovery;
import com.im.api.IRouteTable;
import com.im.api.NodeInformation;
import com.im.config.Config;
import com.im.core.delivery.RedisClusterMessageBus;
import com.im.core.discovery.RedisNodeDiscovery;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisRouteTable;
import com.im.core.session.SessionManager;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

final class RedisComponentsFactory {

    private RedisComponentsFactory() {
    }

    static RedisConfiguration requireRedisConfig(Config config) {
        RedisConfiguration redisConfig = buildRedisConfig(config);
        if (redisConfig == null) {
            throw new IllegalStateException(
                    "Cluster mode requires Redis. Set im.redis.host (and im.db.enabled=true).");
        }
        return redisConfig;
    }

    static ClusterDependencies createCluster(RedisConfiguration redisConfig,
                                             SessionManager sessionManager,
                                             String nodeId,
                                             String routeRedisKeyLayout) {
        IRouteTable routeTable = new RedisRouteTable(redisConfig, sessionManager, nodeId, routeRedisKeyLayout);
        return new ClusterDependencies(
                routeTable,
                new RedisClusterMessageBus(redisConfig, nodeId),
                nodeDiscovery(redisConfig, routeTable));
    }

    static NodeInformation buildNodeInformation(Config config, String nodeId) {
        String host = BootstrapDefaults.LOOPBACK_HOST;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            // Keep startup tolerant in local/dev networks where host discovery can fail;
            // Redis node discovery still needs a stable fallback address.
        }
        int servicePort = config.getBoolean("im.ws.enabled", true)
                ? config.getInt("im.ws.port", BootstrapDefaults.WS_PORT)
                : 0;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("webSocketPort", String.valueOf(servicePort));
        return new NodeInformation(nodeId, host, servicePort, attrs);
    }

    private static INodeDiscovery nodeDiscovery(RedisConfiguration redisConfig, IRouteTable routeTable) {
        return new RedisNodeDiscovery(redisConfig, routeTable);
    }

    private static RedisConfiguration buildRedisConfig(Config config) {
        String redisHost = config.getString("im.redis.host").orElse(null);
        if (redisHost == null || redisHost.isEmpty()) return null;

        int redisPort = config.getInt("im.redis.port", BootstrapDefaults.REDIS_PORT);
        String redisUsername = config.getString("im.redis.username").orElse("");
        String redisPassword = config.getString("im.redis.password").orElse("");
        int redisDatabase = config.getInt("im.redis.database", 0);
        String redisClusterNodes = config.getString("im.redis.cluster.nodes").orElse(null);

        RedisConfiguration.Builder rcb = RedisConfiguration.builder()
                .username(redisUsername)
                .password(redisPassword)
                .database(redisDatabase);
        if (redisClusterNodes != null && !redisClusterNodes.isEmpty()) {
            rcb.clusterNodes(redisClusterNodes.split(","));
        } else {
            rcb.host(redisHost).port(redisPort);
        }
        return rcb.build();
    }
}
