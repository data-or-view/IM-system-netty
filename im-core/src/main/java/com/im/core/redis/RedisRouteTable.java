package com.im.core.redis;

import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.RouteNode;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Redis 驱动路由表 + 在线状态管理。
 *
 * 数据模型（Redis）：
 * <pre>
 *   route:{userId}     → nodeId        (String, TTL=180s)  ← 节点路由
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

    private final RedisAsyncCommands<String, String> async;
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
    public void online(String userId, String nodeId) {
        String key = KEY_ROUTE_PREFIX + userId;
        async.setex(key, ROUTE_TTL_SECONDS, nodeId);
        log.info("Route online: userId={}, node={}", userId, nodeId);
    }

    @Override
    public void offline(String userId, String nodeId) {
        String key = KEY_ROUTE_PREFIX + userId;
        async.del(key);
        log.info("Route offline: userId={}, node={}", userId, nodeId);
    }

    @Override
    public RouteNode lookup(String userId) {
        // 先查本地
        if (sessionManager.getByUserId(userId) != null) {
            return RouteNode.local(localNodeId);
        }
        // 查 Redis（集群模式下，用户可能在别的节点）
        String nodeId = async.get(KEY_ROUTE_PREFIX + userId).toCompletableFuture().join();
        if (nodeId != null) {
            return RouteNode.remote(nodeId, null, 0);
        }
        return null;
    }

    @Override
    public List<RouteNode> lookupAll(String userId) {
        RouteNode rn = lookup(userId);
        if (rn != null) {
            return List.of(rn);
        }
        return Collections.emptyList();
    }

    // ========== 在线状态（Platform 级别） ==========

    @Override
    public void setOnline(String userId, int platformId) {
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
        );
        log.info("Online set: userId={}, platform={}", userId, platformId);
    }

    @Override
    public void setOffline(String userId, int platformId) {
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
        );
        log.info("Online removed: userId={}, platform={}", userId, platformId);
    }

    @Override
    public List<Integer> getOnlinePlatforms(String userId) {
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
    }

    @Override
    public Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) {
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
    }

    @Override
    public void renewOnline(String userId, int platformId) {
        String key = KEY_ONLINE_PREFIX + userId;
        long expireAt = System.currentTimeMillis() + ONLINE_TTL_SECONDS * 1000L;

        async.zadd(key, expireAt, String.valueOf(platformId));
        async.expire(key, ONLINE_TTL_SECONDS);
        log.trace("Online renewed: userId={}, platform={}", userId, platformId);
    }
}
