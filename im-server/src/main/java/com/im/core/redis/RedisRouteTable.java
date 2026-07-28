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
 *   im:route:v3:{u-&lt;base64url-user-id&gt;}  → {platformId:sessionId: nodeId|expireAt|generation} (Hash, TTL=180s)
 *   im:online:v3:{u-&lt;base64url-user-id&gt;} → {platform: timestamp} (ZSet, TTL=180s)
 *   im:route-node:v3:&lt;node-id&gt;           → {userId|platformId:sessionId|generation} (Set, TTL=210s)
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
    private static final String KEY_ONLINE_PREFIX = "im:online:v3:";

    /** 路由 key 前缀 */
    private static final String KEY_ROUTE_PREFIX = "im:route:v3:";

    /** 节点反向路由索引 key 前缀 */
    private static final String KEY_ROUTE_NODE_PREFIX = "im:route-node:v3:";

    private static final String LEGACY_ONLINE_PREFIX = "online:";
    private static final String LEGACY_ROUTE_PREFIX = "route:";
    private static final String LEGACY_ROUTE_NODE_PREFIX = "route_node:";
    private static final String V2_ONLINE_PREFIX = "im:online:v2:";
    private static final String V2_ROUTE_PREFIX = "im:route:v2:";
    private static final String V2_ROUTE_NODE_PREFIX = "im:route-node:v2:";
    private static final String LAYOUT_MARKER_KEY = "im:route:key-layout";
    private static final String DRAINING_LAYOUT = "draining-v3";
    private static final String TAGGED_LAYOUT = "tagged-v3";

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
            if previous and not string.match(previous, '^[^|]+|%d+|[^|]+$') then
              return redis.error_reply('invalid tagged-v3 route value')
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
            if not string.match(current, '^[^|]+|%d+|[^|]+$') then
              return redis.error_reply('invalid tagged-v3 route value')
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
            local platformPrefix = ARGV[3]
            local platformId = ARGV[4]
            local now = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])
            local expectedGeneration = ARGV[7]
            local current = redis.call('hget', routeKey, field)
            local removedValue = ''
            local currentGeneration = current and string.match(current, '|([^|]+)$') or ''
            if current ~= false and not string.match(current, '^[^|]+|%d+|[^|]+$') then
              return redis.error_reply('invalid tagged-v3 route value')
            end
            if current ~= false and string.sub(current, 1, string.len(expectedNode) + 1) == expectedNode .. '|'
                and (expectedGeneration == '' or currentGeneration == expectedGeneration) then
              if redis.call('hdel', routeKey, field) > 0 then removedValue = current end
            end
            local hasLiveBinding = false
            local entries = redis.call('hgetall', routeKey)
            for index = 1, #entries, 2 do
              local bindingField = entries[index]
              local bindingValue = entries[index + 1]
              if string.sub(bindingField, 1, string.len(platformPrefix)) == platformPrefix then
                if not string.match(bindingValue, '^[^|]+|%d+|[^|]+$') then
                  return redis.error_reply('invalid tagged-v3 route value')
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
    private final KeyLayout keyLayout;

    /** Lua SHA 缓存 */
    private volatile String shaSetOnline;
    private volatile String shaSetOffline;

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager, String localNodeId) {
        this(redisConfig, sessionManager, localNodeId, TAGGED_LAYOUT);
    }

    public RedisRouteTable(RedisConfiguration redisConfig, ISessionManager sessionManager,
                           String localNodeId, String keyLayout) {
        this.async = redisConfig.async();
        this.redisConfig = redisConfig;
        this.sessionManager = sessionManager;
        this.localNodeId = localNodeId;
        this.keyLayout = KeyLayout.parse(keyLayout);
        ensureLayoutReady();
        reconcileNodeIndex(localNodeId);
        log.info("RedisRouteTable created: nodeId={}, keyLayout={}", localNodeId, this.keyLayout.value);
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
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("route online", () -> {
            String key = routeKey(userId);
            String field = routeField(platformId, sessionId);
            String generation = UUID.randomUUID().toString();
            String previous = (String) async.eval(LUA_REGISTER_ROUTE, ScriptOutputType.VALUE,
                            new String[]{key}, field, routeValue(nodeId, routeExpireAt(), generation),
                            String.valueOf(ROUTE_TTL_SECONDS))
                    .toCompletableFuture().join();
            addNodeRouteIndex(nodeId, userId, field, generation);
            if (previous != null && !previous.isBlank()) {
                RouteValue old = parseRouteValue(previous);
                if (nodeId.equals(old.nodeId())) removeNodeRouteIndex(nodeId, userId, field, old.generation());
            }
            log.info("Route online: userId={}, node={}, platform={}, session={}",
                    userId, nodeId, platformId, sessionId);
            return null;
        });
    }

    @Override
    public void offline(String userId, String nodeId, int platformId, String sessionId) {
        requireLayoutReady(userId);
        PersistenceExceptions.runRedis("route offline", () -> {
            String field = routeField(platformId, sessionId);
            String removedValue = removeRoute(userId, nodeId, platformId, field, "");
            boolean removed = removedValue != null && !removedValue.isBlank();
            if (removed) {
                removeNodeRouteIndex(nodeId, userId, field, parseRouteValue(removedValue).generation());
            }
            log.info("Route offline: userId={}, node={}, platform={}, session={}, removed={}",
                    userId, nodeId, platformId, sessionId, removed);
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
        userIds.forEach(this::requireNoPreV3UserState);
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
        requireLayoutMarker();
        if (Long.valueOf(1L).equals(async.exists(LEGACY_ROUTE_NODE_PREFIX + nodeId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ROUTE_NODE_PREFIX + nodeId).toCompletableFuture().join())) {
            throw new IllegalStateException("pre-v3 route-node index exists for node " + nodeId
                    + "; tagged-v3 cleanup refused");
        }
        return PersistenceExceptions.runRedis("cleanup node routes", () -> {
            String nodeIndexKey = nodeIndexKey(nodeId);
            Set<String> entries = new LinkedHashSet<>();
            Set<String> indexed = async.smembers(nodeIndexKey).toCompletableFuture().join();
            if (indexed != null) entries.addAll(indexed);
            entries.addAll(authoritativeNodeEntries(nodeId, false));
            int count = cleanupNodeRoutes(nodeId, entries);
            log.info("Node routes cleaned: nodeId={}, removed={}", nodeId, count);
            return count;
        });
    }

    int cleanupNodeRoutes(String nodeId, Set<String> entries) {
        int count = 0;
        for (String entry : entries) {
            NodeIndexEntry parsed = parseNodeIndexEntry(entry);
            int platformId = Integer.parseInt(parsed.field().substring(0, parsed.field().indexOf(':')));
            String removedValue = removeRoute(parsed.userId(), nodeId, platformId,
                    parsed.field(), parsed.generation());
            if (removedValue != null && !removedValue.isBlank()) count++;
            removeNodeRouteIndex(nodeId, parsed.userId(), parsed.field(), parsed.generation());
        }
        return count;
    }

    private static String routeField(int platformId, String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return platformId + ":" + sid;
    }

    private static String routeValue(String nodeId, long expireAt, String generation) {
        return nodeId + "|" + expireAt + "|" + generation;
    }

    private static long routeExpireAt() {
        return System.currentTimeMillis() + ROUTE_TTL_SECONDS * 1000L;
    }

    boolean renewRouteBinding(String userId, int platformId, String sessionId, String expectedValue) {
        String routeKey = routeKey(userId);
        String field = routeField(platformId, sessionId);
        RouteValue current = parseRouteValue(expectedValue);
        if (!localNodeId.equals(current.nodeId())) return false;
        long expireAt = routeExpireAt();
        String nextGeneration = UUID.randomUUID().toString();
        String replacement = routeValue(current.nodeId(), expireAt, nextGeneration);
        Number renewed = (Number) async.eval(LUA_RENEW_ROUTE, ScriptOutputType.INTEGER,
                        new String[]{routeKey, onlineKey(userId)}, field, expectedValue, replacement,
                        String.valueOf(platformId), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(expireAt), String.valueOf(ROUTE_TTL_SECONDS))
                .toCompletableFuture().join();
        if (renewed == null || renewed.longValue() == 0) return false;
        addNodeRouteIndex(current.nodeId(), userId, field, nextGeneration);
        removeNodeRouteIndex(current.nodeId(), userId, field, current.generation());
        return true;
    }

    private void addNodeRouteIndex(String nodeId, String userId, String routeField, String generation) {
        String key = nodeIndexKey(nodeId);
        async.sadd(key, nodeIndexEntry(userId, routeField, generation)).toCompletableFuture().join();
        async.expire(key, ROUTE_NODE_INDEX_TTL_SECONDS).toCompletableFuture().join();
    }

    private void removeNodeRouteIndex(String nodeId, String userId, String routeField, String generation) {
        if (generation == null || generation.isBlank()) return;
        async.srem(nodeIndexKey(nodeId), nodeIndexEntry(userId, routeField, generation))
                .toCompletableFuture().join();
    }

    private String removeRoute(String userId, String nodeId, int platformId, String field, String generation) {
        long now = System.currentTimeMillis();
        return (String) async.eval(LUA_REMOVE_ROUTE, ScriptOutputType.VALUE,
                new String[]{routeKey(userId), onlineKey(userId)}, field, nodeId, platformId + ":",
                String.valueOf(platformId), String.valueOf(now), String.valueOf(ONLINE_TTL_SECONDS), generation)
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

    private static String nodeIndexEntry(String userId, String routeField, String generation) {
        return userId + "|" + routeField + "|" + generation;
    }

    private static RouteBinding toRouteBinding(String userId, String routeField, String routeValue) {
        validateRouteField(routeField);
        String[] parts = routeField.split(":", 2);
        int platformId = Integer.parseInt(parts[0]);
        String sessionId = parts[1];
        RouteValue value = parseRouteValue(routeValue);
        String nodeId = value.nodeId();
        long expireAt = value.expireAt();
        return new RouteBinding(userId, nodeId, platformId, sessionId, expireAt);
    }

    private static RouteValue parseRouteValue(String routeValue) {
        if (routeValue == null) {
            throw new IllegalStateException("route value must include node, expiry, and generation");
        }
        String[] valueParts = routeValue.split("\\|", -1);
        if (valueParts.length != 3 || valueParts[0].isBlank() || valueParts[2].isBlank()) {
            throw new IllegalStateException("route value must include node, expiry, and generation: " + routeValue);
        }
        String nodeId = valueParts[0];
        long expireAt;
        try {
            expireAt = Long.parseLong(valueParts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("route expiry must be an epoch millisecond: " + routeValue, e);
        }
        String generation = valueParts[2];
        return new RouteValue(nodeId, expireAt, generation);
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
        if (separator <= 0 || generationSeparator <= separator || generationSeparator == member.length() - 1) {
            throw new IllegalStateException("route reverse-index member must include generation: " + member);
        }
        String field = member.substring(separator + 1, generationSeparator);
        validateRouteField(field);
        return new NodeIndexEntry(member.substring(0, separator), field,
                member.substring(generationSeparator + 1));
    }

    private record RouteValue(String nodeId, long expireAt, String generation) { }

    private record NodeIndexEntry(String userId, String field, String generation) { }

    private void reconcileNodeIndex(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return;
        for (String member : authoritativeNodeEntries(nodeId, true)) {
            NodeIndexEntry entry = parseNodeIndexEntry(member);
            addNodeRouteIndex(nodeId, entry.userId(), entry.field(), entry.generation());
        }
    }

    private Set<String> authoritativeNodeEntries(String nodeId, boolean liveOnly) {
        Set<String> entries = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            for (String key : scanKeys(commands, KEY_ROUTE_PREFIX + "*")) {
                String userId = userIdFromRouteKey(key);
                for (Map.Entry<String, String> route : commands.hgetall(key).entrySet()) {
                    validateRouteField(route.getKey());
                    RouteValue value = parseRouteValue(route.getValue());
                    if (nodeId.equals(value.nodeId()) && (!liveOnly || value.expireAt() > now)) {
                        entries.add(nodeIndexEntry(userId, route.getKey(), value.generation()));
                    }
                }
            }
        }
        return entries;
    }

    private static String userIdFromRouteKey(String key) {
        if (key == null || !key.startsWith(KEY_ROUTE_PREFIX + "{u-") || !key.endsWith("}")) {
            throw new IllegalStateException("invalid tagged-v3 route key: " + key);
        }
        String encoded = key.substring((KEY_ROUTE_PREFIX + "{u-").length(), key.length() - 1);
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid tagged-v3 route user hash tag: " + key, e);
        }
    }

    private void ensureLayoutReady() {
        if (keyLayout != KeyLayout.TAGGED_V3) {
            throw new IllegalStateException("legacy route Redis key layout is unsafe; use tagged-v3");
        }
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisClusterCommands<String, String> commands = redis.sync();
            for (int attempt = 0; attempt < 4; attempt++) {
                String current = commands.get(LAYOUT_MARKER_KEY);
                if (hasAnyPreV3State(commands)) {
                    throw new IllegalStateException("pre-v3 route state must expire before tagged-v3 cutover");
                }
                validateV3State(commands);
                if (TAGGED_LAYOUT.equals(current)) return;
                if (current != null && !DRAINING_LAYOUT.equals(current)) {
                    throw new IllegalStateException("unsupported route Redis key layout marker: " + current);
                }
                if (!DRAINING_LAYOUT.equals(current)) {
                    if (!compareAndSetLayout(commands, current, DRAINING_LAYOUT)) continue;
                }
                if (hasAnyPreV3State(commands)) {
                    throw new IllegalStateException("pre-v3 route state appeared during tagged-v3 cutover; "
                            + "old binaries must remain stopped");
                }
                validateV3State(commands);
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
        requireNoPreV3UserState(userId);
    }

    private void requireNoPreV3UserState(String userId) {
        if (Long.valueOf(1L).equals(async.exists(LEGACY_ROUTE_PREFIX + userId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(LEGACY_ONLINE_PREFIX + userId).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ROUTE_PREFIX + userHashTag(userId)).toCompletableFuture().join())
                || Long.valueOf(1L).equals(async.exists(V2_ONLINE_PREFIX + userHashTag(userId)).toCompletableFuture().join())) {
            throw new IllegalStateException("pre-v3 route state exists for user " + userId
                    + "; tagged-v3 operation refused");
        }
    }

    private void requireLayoutMarker() {
        String current = async.get(LAYOUT_MARKER_KEY).toCompletableFuture().join();
        if (!TAGGED_LAYOUT.equals(current)) {
            throw new IllegalStateException("route Redis key layout marker changed; tagged-v3 operation refused");
        }
    }

    private static boolean compareAndSetLayout(RedisClusterCommands<String, String> commands,
                                               String expected, String replacement) {
        Long changed = commands.eval(LAYOUT_CAS_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{LAYOUT_MARKER_KEY}, expected != null ? expected : "", replacement);
        return Long.valueOf(1L).equals(changed);
    }

    private static boolean hasAnyPreV3State(RedisClusterCommands<String, String> commands) {
        return !scanKeys(commands, LEGACY_ROUTE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, LEGACY_ONLINE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, LEGACY_ROUTE_NODE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ROUTE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ONLINE_PREFIX + "*").isEmpty()
                || !scanKeys(commands, V2_ROUTE_NODE_PREFIX + "*").isEmpty();
    }

    private static void validateV3State(RedisClusterCommands<String, String> commands) {
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

    private enum KeyLayout {
        TAGGED_V3(TAGGED_LAYOUT);

        private final String value;

        KeyLayout(String value) {
            this.value = value;
        }

        private static KeyLayout parse(String value) {
            if (TAGGED_LAYOUT.equalsIgnoreCase(value)) return TAGGED_V3;
            throw new IllegalArgumentException("unsupported route Redis key layout: " + value);
        }
    }
}
