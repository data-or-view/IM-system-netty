package com.im.core.redis;

import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.common.exception.PersistenceExceptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 驱动路由表 + 在线状态管理。
 *
 * 数据模型（Redis）：
 * <pre>
 *   route:{userId}     → {platformId:sessionId: nodeId|expireAt} (Hash, TTL=180s) ← 节点路由
 *   online:{userId}    → {platform: timestamp} (ZSet, TTL=180s) ← 在线状态
 * </pre>
 *
 * Lua 脚本（set_online）：
 *   1. ZREMRANGEBYSCORE key -inf now   ← 清理过期 platform
 *   2. ZADD key expireAt platformId     ← 添加/刷新 platform
 *   3. EXPIRE key 180                   ← 续 TTL
 *   4. 返回当前所有在线 platform
 *
 * 线程安全：Lettuce RedisAsyncCommands 是线程安全的，所有操作共享同一连接。
 */
public class RedisRouteTable implements IRouteTable {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteTable.class);

    /** 在线状态缓存 TTL（秒） */
    private static final long ONLINE_TTL_SECONDS = 180;

    /** 路由缓存 TTL（秒） */
    private static final long ROUTE_TTL_SECONDS = 180;

    /** 在线状态 ZSet key 前缀 */
    private static final String KEY_ONLINE_PREFIX = "online:";

    /** 路由 key 前缀 */
    private static final String KEY_ROUTE_PREFIX = "route:";

    /** Redis 前缀 Lua 脚本：原子更新在线状态 */
    private static final String LUA_SET_ONLINE = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local expireAt = tonumber(ARGV[2])
            local platformId = ARGV[3]
            local ttl = tonumber(ARGV[4])
            -- 清理已过期的 platform
            redis.call("ZREMRANGEBYSCORE", key, "-inf", now)
            -- 添加/刷新当前 platform
            redis.call("ZADD", key, expireAt, platformId)
            -- 续 TTL
            redis.call("EXPIRE", key, ttl)
            -- 返回当前所有在线的 platform
            return redis.call("ZRANGEBYSCORE", key, now, "+inf")
            """;

    /** Redis 前缀 Lua 脚本：原子移除离线状态 */
    private static final String LUA_SET_OFFLINE = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local platformId = ARGV[2]
            local ttl = tonumber(ARGV[3])
            -- 清理已过期的 platform
            redis.call("ZREMRANGEBYSCORE", key, "-inf", now)
            -- 移除指定 platform
            redis.call("ZREM", key, platformId)
            -- 如果还有在线的 platform，续 TTL；否则让 key 过期
            local count = redis.call("ZCARD", key)
            if count > 0 then
                redis.call("EXPIRE", key, ttl)
            end
            -- 返回剩余在线 platform
            return redis.call("ZRANGEBYSCORE", key, now, "+inf")
            """;

    private final RedisClusterAsyncCommands<String, String> async;
    private final RedisConfiguration redisConfig;
    private final ISessionManager sessionManager;
    private final String localNodeId;

    /** Lua SHA 缓存 */
    private volatile String shaSetOnline;
    private volatile String shaSetOffline;

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager, String localNodeId) {
        this.async = redisConfig.async();
        this.redisConfig = redisConfig;
        this.sessionManager = sessionManager;
        this.localNodeId = localNodeId;
        log.info("RedisRouteTable created: nodeId={}", localNodeId);
    }

    /**
     * 获取 Redis 配置引用，用于关闭时释放资源。
     */
    public RedisConfiguration getRedisConfig() {
        return redisConfig;
    }

    // ========== 节点路由 ==========

    @Override
    public void online(String userId, String nodeId, int platformId, String sessionId) {
        PersistenceExceptions.runRedis("route online", () -> {
            String key = KEY_ROUTE_PREFIX + userId;
            async.hset(key, routeField(platformId, sessionId), routeValue(nodeId, routeExpireAt()))
                    .toCompletableFuture().join();
            async.expire(key, ROUTE_TTL_SECONDS).toCompletableFuture().join();
            log.info("Route online: userId={}, node={}, platform={}, session={}",
                    userId, nodeId, platformId, sessionId);
            return null;
        });
    }

    @Override
    public void offline(String userId, String nodeId, int platformId, String sessionId) {
        PersistenceExceptions.runRedis("route offline", () -> {
            String key = KEY_ROUTE_PREFIX + userId;
            async.hdel(key, routeField(platformId, sessionId)).toCompletableFuture().join();
            log.info("Route offline: userId={}, node={}, platform={}, session={}",
                    userId, nodeId, platformId, sessionId);
            return null;
        });
    }

    @Override
    public RouteNode lookup(String userId) {
        // 先查本地
        if (sessionManager.getByUserId(userId) != null) {
            return RouteNode.local(localNodeId);
        }
        List<RouteNode> routes = lookupAll(userId);
        return routes.isEmpty() ? null : routes.get(0);
    }

    @Override
    public List<RouteNode> lookupAll(String userId) {
        boolean hasLocalSession = sessionManager.getByUserId(userId) != null;
        List<RouteBinding> bindings = lookupAllBindings(userId);

        if (bindings.isEmpty() && !hasLocalSession) {
            return Collections.emptyList();
        }

        java.util.ArrayList<RouteNode> result = new java.util.ArrayList<>();
        if (hasLocalSession) {
            result.add(RouteNode.local(localNodeId));
        }
        bindings.stream()
                .map(RouteBinding::nodeId)
                .distinct()
                .filter(nodeId -> nodeId != null && !nodeId.equals(localNodeId))
                .map(nodeId -> RouteNode.remote(nodeId, nodeId, 0))
                .forEach(result::add);
        return result;
    }

    @Override
    public List<RouteBinding> lookupAllBindings(String userId) {
        return PersistenceExceptions.runRedis("lookup route bindings", () -> {
            long now = System.currentTimeMillis();
            Map<String, String> routes = async.hgetall(KEY_ROUTE_PREFIX + userId)
                    .toCompletableFuture()
                    .join();
            if (routes == null || routes.isEmpty()) {
                return Collections.emptyList();
            }
            return routes.entrySet().stream()
                    .map(entry -> toRouteBinding(userId, entry.getKey(), entry.getValue()))
                    .filter(binding -> !binding.isExpired(now))
                    .toList();
        });
    }

    // ========== 在线状态（Platform 级别） ==========

    @Override
    public void setOnline(String userId, int platformId) {
        PersistenceExceptions.runRedis("set online platform", () -> {
            String key = KEY_ONLINE_PREFIX + userId;
            long now = System.currentTimeMillis();
            long expireAt = now + ONLINE_TTL_SECONDS * 1000L;

            // 初次加载 Lua 脚本（缓存 SHA 后不再重新加载）
            if (shaSetOnline == null) {
                shaSetOnline = async.scriptLoad(LUA_SET_ONLINE).toCompletableFuture().join();
            }

            async.evalsha(shaSetOnline, ScriptOutputType.MULTI,
                    new String[]{key},
                    String.valueOf(now),
                    String.valueOf(expireAt),
                    String.valueOf(platformId),
                    String.valueOf(ONLINE_TTL_SECONDS)
            ).toCompletableFuture().join();
            log.info("Online set: userId={}, platform={}", userId, platformId);
            return null;
        });
    }

    @Override
    public void setOffline(String userId, int platformId) {
        PersistenceExceptions.runRedis("set offline platform", () -> {
            String key = KEY_ONLINE_PREFIX + userId;
            long now = System.currentTimeMillis();

            if (shaSetOffline == null) {
                shaSetOffline = async.scriptLoad(LUA_SET_OFFLINE).toCompletableFuture().join();
            }

            async.evalsha(shaSetOffline, ScriptOutputType.MULTI,
                    new String[]{key},
                    String.valueOf(now),
                    String.valueOf(platformId),
                    String.valueOf(ONLINE_TTL_SECONDS)
            ).toCompletableFuture().join();
            log.info("Online removed: userId={}, platform={}", userId, platformId);
            return null;
        });
    }

    @Override
    public List<Integer> getOnlinePlatforms(String userId) {
        return PersistenceExceptions.runRedis("get online platforms", () -> {
            String key = KEY_ONLINE_PREFIX + userId;
            long now = System.currentTimeMillis();

            @SuppressWarnings("unchecked")
            List<String> platforms = (List<String>) async.zrangebyscore(
                    key,
                    String.valueOf(now),
                    "+inf"
            ).toCompletableFuture().join();

            if (platforms == null || platforms.isEmpty()) {
                return Collections.emptyList();
            }
            return platforms.stream()
                    .map(Integer::parseInt)
                    .toList();
        });
    }

    @Override
    public Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) {
        return PersistenceExceptions.runRedis("batch get online platforms", () -> {
            long now = System.currentTimeMillis();
            Map<String, List<Integer>> result = new ConcurrentHashMap<>();

            java.util.List<CompletableFuture<Void>> futures = userIds.stream()
                    .map(userId -> {
                        String key = KEY_ONLINE_PREFIX + userId;
                        return async.zrangebyscore(key, String.valueOf(now), "+inf")
                                .thenAccept(platforms -> {
                                    if (platforms != null && !platforms.isEmpty()) {
                                        result.put(userId, platforms.stream()
                                                .map(Integer::parseInt)
                                                .toList());
                                    }
                                });
                    })
                    .map(f -> (CompletableFuture<Void>) f.toCompletableFuture())
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return result;
        });
    }

    @Override
    public void renewOnline(String userId, int platformId) {
        renewOnline(userId, platformId, "default");
    }

    @Override
    public void renewOnline(String userId, int platformId, String sessionId) {
        PersistenceExceptions.runRedis("renew online platform", () -> {
            String key = KEY_ONLINE_PREFIX + userId;
            long expireAt = System.currentTimeMillis() + ONLINE_TTL_SECONDS * 1000L;

            async.zadd(key, expireAt, String.valueOf(platformId)).toCompletableFuture().join();
            async.expire(key, ONLINE_TTL_SECONDS).toCompletableFuture().join();
            renewRouteBinding(userId, platformId, sessionId);
            log.trace("Online renewed: userId={}, platform={}, session={}", userId, platformId, sessionId);
            return null;
        });
    }

    private static String routeField(int platformId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return platformId + ":" + sid;
    }

    private static String routeValue(String nodeId, long expireAt) {
        return nodeId + "|" + expireAt;
    }

    private static long routeExpireAt() {
        return System.currentTimeMillis() + ROUTE_TTL_SECONDS * 1000L;
    }

    private void renewRouteBinding(String userId, int platformId, String sessionId) {
        String routeKey = KEY_ROUTE_PREFIX + userId;
        String field = routeField(platformId, sessionId);
        async.hget(routeKey, field).thenAccept(currentValue -> {
            if (currentValue == null || currentValue.isBlank()) {
                return;
            }
            RouteBinding current = toRouteBinding(userId, field, currentValue);
            async.hset(routeKey, field, routeValue(current.nodeId(), routeExpireAt()));
            async.expire(routeKey, ROUTE_TTL_SECONDS);
        });
    }

    private static RouteBinding toRouteBinding(String userId, String routeField, String routeValue) {
        String[] parts = routeField.split(":", 2);
        int platformId = PlatformID.DEFAULT;
        if (parts.length > 0) {
            try {
                platformId = Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {
                platformId = PlatformID.DEFAULT;
            }
        }
        String sessionId = parts.length > 1 ? parts[1] : "default";
        String[] valueParts = routeValue.split("\\|", 2);
        String nodeId = valueParts[0];
        long expireAt = 0;
        if (valueParts.length > 1) {
            try {
                expireAt = Long.parseLong(valueParts[1]);
            } catch (NumberFormatException ignored) {
                expireAt = 0;
            }
        }
        return new RouteBinding(userId, nodeId, platformId, sessionId, expireAt);
    }
}
