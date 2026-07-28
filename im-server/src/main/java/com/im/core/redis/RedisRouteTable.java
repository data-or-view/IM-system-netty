package com.im.core.redis;

import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.common.exception.PersistenceExceptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 驱动路由表 + 在线状态管理。
 *
 * 数据模型（Redis）：
 * <pre>
 *   im:route:v4:{u-&lt;base64url-user-id&gt;}  → {platformId:sessionId: nodeId|expireAt|incarnation|generation}
 *   im:online:v4:{u-&lt;base64url-user-id&gt;} → {platform: timestamp} (ZSet, TTL=180s)
 *   im:route-node:v4:&lt;node-id&gt;           → {userId|platformId:sessionId|incarnation|generation}
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
    private static final String KEY_ONLINE_PREFIX = "im:online:v4:";

    /** 路由 key 前缀 */
    private static final String KEY_ROUTE_PREFIX = "im:route:v4:";

    /** 节点反向路由索引 key 前缀 */
    private static final String KEY_ROUTE_NODE_PREFIX = "im:route-node:v4:";

    private static final String LEGACY_ONLINE_PREFIX = "online:";
    private static final String LEGACY_ROUTE_PREFIX = "route:";
    private static final String LEGACY_ROUTE_NODE_PREFIX = "route_node:";
    private static final String V2_ONLINE_PREFIX = "im:online:v2:";
    private static final String V2_ROUTE_PREFIX = "im:route:v2:";
    private static final String V2_ROUTE_NODE_PREFIX = "im:route-node:v2:";
    private static final String V3_ONLINE_PREFIX = "im:online:v3:";
    private static final String V3_ROUTE_PREFIX = "im:route:v3:";
    private static final String V3_ROUTE_NODE_PREFIX = "im:route-node:v3:";
    private static final String LAYOUT_MARKER_KEY = "im:route:key-layout";
    private static final String DRAINING_LAYOUT = "draining-v4";
    private static final String TAGGED_LAYOUT = "tagged-v4";

    /** 节点反向路由索引 TTL（秒），略长于 route TTL 便于节点过期清理 */
    private static final long ROUTE_NODE_INDEX_TTL_SECONDS = ROUTE_TTL_SECONDS + 30;

    private static final String LAYOUT_CAS_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            if ARGV[1] == '' then
              if current then return 0 end
            elseif current ~= ARGV[1] then
              return 0
            end
            redis.call('set', KEYS[1], ARGV[2])
            return 1
            """;

    private static final String LUA_REGISTER_ROUTE = """
            local routeKey = KEYS[1]
            local field = ARGV[1]
            local routeValue = ARGV[2]
            local ttl = tonumber(ARGV[3])
            local previous = redis.call('hget', routeKey, field)
            if previous and not string.match(previous, '^[^|]+|%d+|[^|]+|[^|]+$') then
              return redis.error_reply('invalid tagged-v4 route value')
            end
            redis.call('hset', routeKey, field, routeValue)
            redis.call('expire', routeKey, ttl)
            return previous or ''
            """;

    private static final String LUA_RENEW_ROUTE = """
            local routeKey = KEYS[1]
            local onlineKey = KEYS[2]
            local field = ARGV[1]
            local expected = ARGV[2]
            local replacement = ARGV[3]
            local platformId = ARGV[4]
            local now = tonumber(ARGV[5])
            local expireAt = tonumber(ARGV[6])
            local ttl = tonumber(ARGV[7])
            local current = redis.call('hget', routeKey, field)
            if current ~= expected then return 0 end
            if not string.match(current, '^[^|]+|%d+|[^|]+|[^|]+$') then
              return redis.error_reply('invalid tagged-v4 route value')
            end
            redis.call('hset', routeKey, field, replacement)
            redis.call('expire', routeKey, ttl)
            redis.call('zremrangebyscore', onlineKey, '-inf', now)
            redis.call('zadd', onlineKey, expireAt, platformId)
            redis.call('expire', onlineKey, ttl)
            return 1
            """;

    /** Deletes one route and only removes the platform when no live binding remains. */
    private static final String LUA_REMOVE_ROUTE = """
            local routeKey = KEYS[1]
            local onlineKey = KEYS[2]
            local field = ARGV[1]
            local expectedNode = ARGV[2]
            local expectedIncarnation = ARGV[3]
            local platformPrefix = ARGV[4]
            local platformId = ARGV[5]
            local now = tonumber(ARGV[6])
            local ttl = tonumber(ARGV[7])
            local expectedGeneration = ARGV[8]
            local current = redis.call('hget', routeKey, field)
            local removedValue = ''
            local nodeSeparator = current and string.find(current, '|', 1, true)
            local expirySeparator = nodeSeparator and string.find(current, '|', nodeSeparator + 1, true)
            local incarnationSeparator = expirySeparator and string.find(current, '|', expirySeparator + 1, true)
            local currentIncarnation = incarnationSeparator and string.sub(current, expirySeparator + 1,
                incarnationSeparator - 1) or ''
            local currentGeneration = incarnationSeparator and string.sub(current, incarnationSeparator + 1) or ''
            if current ~= false and not string.match(current, '^[^|]+|%d+|[^|]+|[^|]+$') then
              return redis.error_reply('invalid tagged-v4 route value')
            end
            if current ~= false and string.sub(current, 1, string.len(expectedNode) + 1) == expectedNode .. '|'
                and currentIncarnation == expectedIncarnation and currentGeneration == expectedGeneration then
              if redis.call('hdel', routeKey, field) > 0 then removedValue = current end
            end
            local hasLiveBinding = false
            local entries = redis.call('hgetall', routeKey)
            for index = 1, #entries, 2 do
              local bindingField = entries[index]
              local bindingValue = entries[index + 1]
              if string.sub(bindingField, 1, string.len(platformPrefix)) == platformPrefix then
                if not string.match(bindingValue, '^[^|]+|%d+|[^|]+|[^|]+$') then
                  return redis.error_reply('invalid tagged-v4 route value')
                end
                local separator = string.find(bindingValue, '|', 1, true)
                local generationSeparator = separator and string.find(bindingValue, '|', separator + 1, true)
                local expiresAt = separator and tonumber(string.sub(bindingValue, separator + 1,
                    generationSeparator and generationSeparator - 1 or -1)) or 0
                if expiresAt > now then
                  hasLiveBinding = true
                else
                  redis.call('hdel', routeKey, bindingField)
                end
              end
            end
            if not hasLiveBinding then
              redis.call('zremrangebyscore', onlineKey, '-inf', now)
              redis.call('zrem', onlineKey, platformId)
              if redis.call('zcard', onlineKey) > 0 then redis.call('expire', onlineKey, ttl) end
            end
            return removedValue
            """;

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
    private final String localNodeIncarnation;
    private final KeyLayout keyLayout;

    /** Lua SHA 缓存 */
    private volatile String shaSetOnline;
    private volatile String shaSetOffline;

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager, String localNodeId) {
        this(redisConfig, sessionManager, localNodeId, UUID.randomUUID().toString(), TAGGED_LAYOUT);
    }

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager,
                           String localNodeId, String keyLayout) {
        this(redisConfig, sessionManager, localNodeId, UUID.randomUUID().toString(), keyLayout);
    }

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager,
                           String localNodeId, String localNodeIncarnation, String keyLayout) {
        this.async = redisConfig.async();
        this.redisConfig = redisConfig;
        this.sessionManager = sessionManager;
        this.localNodeId = localNodeId;
        this.localNodeIncarnation = requireIdentity(localNodeIncarnation, "localNodeIncarnation");
        this.keyLayout = KeyLayout.parse(keyLayout);
        ensureLayoutReady();
        reconcileNodeIndex(localNodeId, localNodeIncarnation);
        log.info("RedisRouteTable created: nodeId={}, incarnation={}, keyLayout={}",
                localNodeId, localNodeIncarnation, this.keyLayout.value);
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
        if (!localNodeId.equals(nodeId)) {
            throw new IllegalArgumentException("route registration for another node requires its incarnation");
        }
        online(userId, nodeId, localNodeIncarnation, platformId, sessionId);
    }

    public void online(String userId, String nodeId, String nodeIncarnation, int platformId, String sessionId) {
        requireLayoutReady(userId);
        String incarnation = requireIdentity(nodeIncarnation, "nodeIncarnation");
        PersistenceExceptions.runRedis("route online", () -> {
            String key = routeKey(userId);
            String field = routeField(platformId, sessionId);
            String generation = UUID.randomUUID().toString();
            String previous = (String) async.eval(LUA_REGISTER_ROUTE, ScriptOutputType.VALUE,
                            new String[]{key}, field, routeValue(nodeId, routeExpireAt(), incarnation, generation),
                            String.valueOf(ROUTE_TTL_SECONDS))
                    .toCompletableFuture().join();
            addNodeRouteIndex(nodeId, userId, field, incarnation, generation);
            if (previous != null && !previous.isBlank()) {
                RouteValue old = parseRouteValue(previous);
                removeNodeRouteIndex(old.nodeId(), userId, field, old.nodeIncarnation(), old.generation());
            }
            log.info("Route online: userId={}, node={}, incarnation={}, platform={}, session={}",
                    userId, nodeId, incarnation, platformId, sessionId);
            return null;
        });
    }

    @Override
    public void offline(String userId, String nodeId, int platformId, String sessionId) {
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("route offline", () -> {
            if (!localNodeId.equals(nodeId)) {
                log.warn("Refusing normal disconnect cleanup for non-local node: localNode={}, requestedNode={}",
                        localNodeId, nodeId);
                return null;
            }
            String field = routeField(platformId, sessionId);
            boolean removed = false;
            for (int attempt = 0; attempt < 3 && !removed; attempt++) {
                String currentValue = async.hget(routeKey(userId), field).toCompletableFuture().join();
                if (currentValue == null || currentValue.isBlank()) break;
                RouteBinding observed = toRouteBinding(userId, field, currentValue);
                if (!nodeId.equals(observed.nodeId())
                        || !localNodeIncarnation.equals(observed.nodeIncarnation())) break;
                String removedValue = removeRoute(observed);
                removed = removedValue != null && !removedValue.isBlank();
                if (removed) {
                    removeNodeRouteIndex(observed.nodeId(), observed.userId(), field,
                            observed.nodeIncarnation(), observed.generation());
                }
            }
            log.info("Route offline: userId={}, node={}, platform={}, session={}, removed={}",
                    userId, nodeId, platformId, sessionId, removed);
            return null;
        });
    }

    @Override
    public void offline(RouteBinding binding) {
        offlineIfCurrent(binding);
    }

    @Override
    public boolean offlineIfCurrent(RouteBinding binding) {
        if (binding == null) return false;
        requireLayoutReady(binding.userId());
        return PersistenceExceptions.runRedis("conditional route offline", () -> {
            if (!binding.hasExactIdentity()) {
                log.debug("Route snapshot lacks incarnation or generation; refusing conditional removal: "
                                + "userId={}, node={}, platform={}, session={}",
                        binding.userId(), binding.nodeId(), binding.platformId(), binding.sessionId());
                return false;
            }
            String field = binding.routeField();
            String removedValue = removeRoute(binding);
            boolean removed = removedValue != null && !removedValue.isBlank();
            if (removed) {
                removeNodeRouteIndex(binding.nodeId(), binding.userId(), field,
                        binding.nodeIncarnation(), binding.generation());
            }
            return removed;
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
        requireLayoutReady(userId);
        return PersistenceExceptions.runRedis("lookup route bindings", () -> {
            long now = System.currentTimeMillis();
            Map<String, String> routes = async.hgetall(routeKey(userId))
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
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("set online platform", () -> {
            String key = onlineKey(userId);
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
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("set offline platform", () -> {
            String key = onlineKey(userId);
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
        requireLayoutReady(userId);
        return PersistenceExceptions.runRedis("get online platforms", () -> {
            String key = onlineKey(userId);
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
        requireLayoutMarker();
        userIds.forEach(this::requireNoPreV4UserState);
        return PersistenceExceptions.runRedis("batch get online platforms", () -> {
            long now = System.currentTimeMillis();
            Map<String, List<Integer>> result = new ConcurrentHashMap<>();

            java.util.List<CompletableFuture<Void>> futures = userIds.stream()
                    .map(userId -> {
                        String key = onlineKey(userId);
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
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("renew online platform", () -> {
            String field = routeField(platformId, sessionId);
            String currentValue = async.hget(routeKey(userId), field).toCompletableFuture().join();
            if (currentValue != null && !currentValue.isBlank()) {
                renewRouteBinding(userId, platformId, sessionId, currentValue);
            }
            log.trace("Online renewed: userId={}, platform={}, session={}", userId, platformId, sessionId);
            return null;
        });
    }

    @Override
    public int cleanupNodeRoutes(String nodeId) {
        log.warn("Refusing route cleanup without node incarnation: nodeId={}", nodeId);
        return 0;
    }

    @Override
    public int cleanupNodeRoutes(String nodeId, String nodeIncarnation) {
        requireLayoutMarker();
        String incarnation = requireIdentity(nodeIncarnation, "nodeIncarnation");
        if (Long.valueOf(1L).equals(async.exists(LEGACY_ROUTE_NODE_PREFIX + nodeId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ROUTE_NODE_PREFIX + nodeId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V3_ROUTE_NODE_PREFIX + nodeId).toCompletableFuture().join())) {
            throw new IllegalStateException("pre-v4 route-node index exists for node " + nodeId
                    + "; tagged-v4 cleanup refused");
        }
        return PersistenceExceptions.runRedis("cleanup node routes", () -> {
            String nodeIndexKey = nodeIndexKey(nodeId);
            Set<String> entries = new LinkedHashSet<>();
            Set<String> indexed = async.smembers(nodeIndexKey).toCompletableFuture().join();
            if (indexed != null) entries.addAll(indexed);
            entries.addAll(authoritativeNodeEntries(nodeId, incarnation, false));
            int count = cleanupNodeRoutes(nodeId, incarnation, entries);
            log.info("Node routes cleaned: nodeId={}, incarnation={}, removed={}", nodeId, incarnation, count);
            return count;
        });
    }

    int cleanupNodeRoutes(String nodeId, String nodeIncarnation, Set<String> entries) {
        int count = 0;
        for (String entry : entries) {
            NodeIndexEntry parsed = parseNodeIndexEntry(entry);
            if (!nodeIncarnation.equals(parsed.nodeIncarnation())) continue;
            int platformId = Integer.parseInt(parsed.field().substring(0, parsed.field().indexOf(':')));
            String removedValue = removeRoute(parsed.userId(), nodeId, platformId,
                    parsed.field(), nodeIncarnation, parsed.generation());
            if (removedValue != null && !removedValue.isBlank()) count++;
            removeNodeRouteIndex(nodeId, parsed.userId(), parsed.field(),
                    nodeIncarnation, parsed.generation());
        }
        return count;
    }

    private static String routeField(int platformId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return platformId + ":" + sid;
    }

    private static String routeValue(String nodeId, long expireAt, String nodeIncarnation, String generation) {
        return nodeId + "|" + expireAt + "|" + nodeIncarnation + "|" + generation;
    }

    private static long routeExpireAt() {
        return System.currentTimeMillis() + ROUTE_TTL_SECONDS * 1000L;
    }

    boolean renewRouteBinding(String userId, int platformId, String sessionId, String expectedValue) {
        String routeKey = routeKey(userId);
        String field = routeField(platformId, sessionId);
        RouteValue current = parseRouteValue(expectedValue);
        if (!localNodeId.equals(current.nodeId())
                || !localNodeIncarnation.equals(current.nodeIncarnation())) return false;
        long expireAt = routeExpireAt();
        String nextGeneration = UUID.randomUUID().toString();
        String replacement = routeValue(current.nodeId(), expireAt, current.nodeIncarnation(), nextGeneration);
        Number renewed = (Number) async.eval(LUA_RENEW_ROUTE, ScriptOutputType.INTEGER,
                        new String[]{routeKey, onlineKey(userId)}, field, expectedValue, replacement,
                        String.valueOf(platformId), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(expireAt), String.valueOf(ROUTE_TTL_SECONDS))
                .toCompletableFuture().join();
        if (renewed == null || renewed.longValue() == 0) return false;
        addNodeRouteIndex(current.nodeId(), userId, field, current.nodeIncarnation(), nextGeneration);
        removeNodeRouteIndex(current.nodeId(), userId, field, current.nodeIncarnation(), current.generation());
        return true;
    }

    private void addNodeRouteIndex(String nodeId, String userId, String routeField,
                                   String nodeIncarnation, String generation) {
        String key = nodeIndexKey(nodeId);
        async.sadd(key, nodeIndexEntry(userId, routeField, nodeIncarnation, generation))
                .toCompletableFuture().join();
        async.expire(key, ROUTE_NODE_INDEX_TTL_SECONDS).toCompletableFuture().join();
    }

    private void removeNodeRouteIndex(String nodeId, String userId, String routeField,
                                      String nodeIncarnation, String generation) {
        if (nodeIncarnation == null || nodeIncarnation.isBlank() || generation == null || generation.isBlank()) return;
        async.srem(nodeIndexKey(nodeId), nodeIndexEntry(userId, routeField, nodeIncarnation, generation))
                .toCompletableFuture().join();
    }

    private String removeRoute(RouteBinding binding) {
        return removeRoute(binding.userId(), binding.nodeId(), binding.platformId(), binding.routeField(),
                binding.nodeIncarnation(), binding.generation());
    }

    private String removeRoute(String userId, String nodeId, int platformId, String field,
                               String nodeIncarnation, String generation) {
        if (nodeIncarnation == null || generation == null) return "";
        long now = System.currentTimeMillis();
        return (String) async.eval(LUA_REMOVE_ROUTE, ScriptOutputType.VALUE,
                new String[]{routeKey(userId), onlineKey(userId)}, field, nodeId, nodeIncarnation,
                platformId + ":", String.valueOf(platformId), String.valueOf(now),
                String.valueOf(ONLINE_TTL_SECONDS), generation)
                .toCompletableFuture().join();
    }

    private static String routeKey(String userId) {
        return KEY_ROUTE_PREFIX + userHashTag(userId);
    }

    private static String onlineKey(String userId) {
        return KEY_ONLINE_PREFIX + userHashTag(userId);
    }

    private static String nodeIndexKey(String nodeId) {
        return KEY_ROUTE_NODE_PREFIX + nodeId;
    }

    private static String nodeIndexEntry(String userId, String routeField,
                                         String nodeIncarnation, String generation) {
        return userId + "|" + routeField + "|" + nodeIncarnation + "|" + generation;
    }

    private static RouteBinding toRouteBinding(String userId, String routeField, String routeValue) {
        validateRouteField(routeField);
        String[] parts = routeField.split(":", 2);
        int platformId = Integer.parseInt(parts[0]);
        String sessionId = parts[1];
        RouteValue value = parseRouteValue(routeValue);
        String nodeId = value.nodeId();
        long expireAt = value.expireAt();
        return new RouteBinding(userId, nodeId, platformId, sessionId, expireAt,
                value.nodeIncarnation(), value.generation());
    }

    private static RouteValue parseRouteValue(String routeValue) {
        if (routeValue == null) {
            throw new IllegalStateException("route value must include node, expiry, incarnation, and generation");
        }
        String[] valueParts = routeValue.split("\\|", -1);
        if (valueParts.length != 4 || valueParts[0].isBlank()
                || valueParts[2].isBlank() || valueParts[3].isBlank()) {
            throw new IllegalStateException(
                    "route value must include node, expiry, incarnation, and generation: " + routeValue);
        }
        String nodeId = valueParts[0];
        long expireAt;
        try {
            expireAt = Long.parseLong(valueParts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("route expiry must be an epoch millisecond: " + routeValue, e);
        }
        String nodeIncarnation = valueParts[2];
        String generation = valueParts[3];
        return new RouteValue(nodeId, expireAt, nodeIncarnation, generation);
    }

    private static void validateRouteField(String routeField) {
        if (routeField == null) {
            throw new IllegalStateException("route field must include platform and session");
        }
        int separator = routeField.indexOf(':');
        if (separator <= 0 || separator == routeField.length() - 1) {
            throw new IllegalStateException("route field must include platform and session: " + routeField);
        }
        try {
            Integer.parseInt(routeField.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("route field platform must be numeric: " + routeField, e);
        }
    }

    private static NodeIndexEntry parseNodeIndexEntry(String member) {
        int separator = member != null ? member.indexOf('|') : -1;
        int generationSeparator = member != null ? member.lastIndexOf('|') : -1;
        int incarnationSeparator = member != null ? member.lastIndexOf('|', generationSeparator - 1) : -1;
        if (separator <= 0 || incarnationSeparator <= separator
                || generationSeparator <= incarnationSeparator || generationSeparator == member.length() - 1) {
            throw new IllegalStateException(
                    "route reverse-index member must include incarnation and generation: " + member);
        }
        String field = member.substring(separator + 1, incarnationSeparator);
        validateRouteField(field);
        return new NodeIndexEntry(member.substring(0, separator), field,
                member.substring(incarnationSeparator + 1, generationSeparator),
                member.substring(generationSeparator + 1));
    }

    private record RouteValue(String nodeId, long expireAt, String nodeIncarnation, String generation) { }

    private record NodeIndexEntry(String userId, String field, String nodeIncarnation, String generation) { }

    private void reconcileNodeIndex(String nodeId, String nodeIncarnation) {
        if (nodeId == null || nodeId.isBlank() || nodeIncarnation == null || nodeIncarnation.isBlank()) return;
        for (String member : authoritativeNodeEntries(nodeId, nodeIncarnation, true)) {
            NodeIndexEntry entry = parseNodeIndexEntry(member);
            addNodeRouteIndex(nodeId, entry.userId(), entry.field(),
                    entry.nodeIncarnation(), entry.generation());
        }
    }

    private Set<String> authoritativeNodeEntries(String nodeId, String nodeIncarnation, boolean liveOnly) {
        Set<String> entries = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            for (String key : scanKeys(commands, KEY_ROUTE_PREFIX + "*")) {
                String userId = userIdFromRouteKey(key);
                for (Map.Entry<String, String> route : commands.hgetall(key).entrySet()) {
                    validateRouteField(route.getKey());
                    RouteValue value = parseRouteValue(route.getValue());
                    if (nodeId.equals(value.nodeId()) && nodeIncarnation.equals(value.nodeIncarnation())
                            && (!liveOnly || value.expireAt() > now)) {
                        entries.add(nodeIndexEntry(userId, route.getKey(),
                                value.nodeIncarnation(), value.generation()));
                    }
                }
            }
        }
        return entries;
    }

    private static String userIdFromRouteKey(String key) {
        if (key == null || !key.startsWith(KEY_ROUTE_PREFIX + "{u-") || !key.endsWith("}")) {
            throw new IllegalStateException("invalid tagged-v4 route key: " + key);
        }
        String encoded = key.substring((KEY_ROUTE_PREFIX + "{u-").length(), key.length() - 1);
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid tagged-v4 route user hash tag: " + key, e);
        }
    }

    private void ensureLayoutReady() {
        if (keyLayout != KeyLayout.TAGGED_V4) {
            throw new IllegalStateException("legacy route Redis key layout is unsafe; use tagged-v4");
        }
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            for (int attempt = 0; attempt < 4; attempt++) {
                String current = commands.get(LAYOUT_MARKER_KEY);
                if (hasAnyPreV4State(commands)) {
                    throw new IllegalStateException("pre-v4 route state must expire before tagged-v4 cutover");
                }
                validateV4State(commands);
                if (TAGGED_LAYOUT.equals(current)) return;
                if (current != null && !DRAINING_LAYOUT.equals(current)) {
                    throw new IllegalStateException("unsupported route Redis key layout marker: " + current);
                }
                if (!DRAINING_LAYOUT.equals(current)) {
                    if (!compareAndSetLayout(commands, current, DRAINING_LAYOUT)) continue;
                }
                if (hasAnyPreV4State(commands)) {
                    throw new IllegalStateException("pre-v4 route state appeared during tagged-v4 cutover; "
                            + "old binaries must remain stopped");
                }
                validateV4State(commands);
                if (compareAndSetLayout(commands, DRAINING_LAYOUT, TAGGED_LAYOUT)
                        || TAGGED_LAYOUT.equals(commands.get(LAYOUT_MARKER_KEY))) {
                    return;
                }
            }
        }
        throw new IllegalStateException("route Redis key layout cutover did not converge");
    }

    private void requireLayoutReady(String userId) {
        requireLayoutMarker();
        requireNoPreV4UserState(userId);
    }

    private void requireNoPreV4UserState(String userId) {
        if (Long.valueOf(1L).equals(async.exists(LEGACY_ROUTE_PREFIX + userId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(LEGACY_ONLINE_PREFIX + userId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ROUTE_PREFIX + userHashTag(userId)).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ONLINE_PREFIX + userHashTag(userId)).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V3_ROUTE_PREFIX + userHashTag(userId)).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V3_ONLINE_PREFIX + userHashTag(userId)).toCompletableFuture().join())) {
            throw new IllegalStateException("pre-v4 route state exists for user " + userId
                    + "; tagged-v4 operation refused");
        }
    }

    private void requireLayoutMarker() {
        String current = async.get(LAYOUT_MARKER_KEY).toCompletableFuture().join();
        if (!TAGGED_LAYOUT.equals(current)) {
            throw new IllegalStateException("route Redis key layout marker changed; tagged-v4 operation refused");
        }
    }

    private static boolean compareAndSetLayout(RedisClusterCommands<String, String> commands,
                                               String expected, String replacement) {
        Long changed = commands.eval(LAYOUT_CAS_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{LAYOUT_MARKER_KEY}, expected != null ? expected : "", replacement);
        return Long.valueOf(1L).equals(changed);
    }

    private static boolean hasAnyPreV4State(RedisClusterCommands<String, String> commands) {
        return !scanKeys(commands, LEGACY_ROUTE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, LEGACY_ONLINE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, LEGACY_ROUTE_NODE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ROUTE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ONLINE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ROUTE_NODE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V3_ROUTE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V3_ONLINE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V3_ROUTE_NODE_PREFIX + "*").isEmpty();
    }

    private static void validateV4State(RedisClusterCommands<String, String> commands) {
        for (String key : scanKeys(commands, KEY_ROUTE_PREFIX + "*")) {
            userIdFromRouteKey(key);
            for (Map.Entry<String, String> entry : commands.hgetall(key).entrySet()) {
                validateRouteField(entry.getKey());
                parseRouteValue(entry.getValue());
            }
        }
        for (String key : scanKeys(commands, KEY_ROUTE_NODE_PREFIX + "*")) {
            for (String member : commands.smembers(key)) {
                parseNodeIndexEntry(member);
            }
        }
    }

    private static Set<String> scanKeys(RedisClusterCommands<String, String> commands, String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = new ScanArgs().match(pattern).limit(500);
        do {
            KeyScanCursor<String> result = commands.scan(cursor, args);
            keys.addAll(result.getKeys());
            cursor = result;
        } while (!cursor.isFinished());
        return keys;
    }

    private static String userHashTag(String userId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        return "{u-" + encoded + "}";
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and must not contain '|'");
        }
        return value;
    }

    private enum KeyLayout {
        TAGGED_V4(TAGGED_LAYOUT);

        private final String value;

        KeyLayout(String value) {
            this.value = value;
        }

        private static KeyLayout parse(String value) {
            if (TAGGED_LAYOUT.equalsIgnoreCase(value)) return TAGGED_V4;
            throw new IllegalArgumentException("unsupported route Redis key layout: " + value);
        }
    }
}
