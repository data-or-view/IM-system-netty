package com.im.core.discovery;

import com.im.api.INodeDiscovery;
import com.im.api.IRouteTable;
import com.im.api.NodeEvent;
import com.im.api.NodeEventListener;
import com.im.api.NodeEventType;
import com.im.api.NodeInformation;
import com.im.common.util.IMExecutors;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed node discovery fenced by a unique token for each process. */
public class RedisNodeDiscovery implements INodeDiscovery {

    private static final Logger log = LoggerFactory.getLogger(RedisNodeDiscovery.class);
    private static final String KEY_NODE = "im:node:";
    private static final String KEY_ALIVE = "im:nodes:alive";
    private static final long NODE_TTL_SEC = 30;
    private static final long HEARTBEAT_INTERVAL_SEC = 10;

    private static final String RENEW_LEASE_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            local incarnation = ARGV[1]
            if not current or string.sub(current, 1, string.len(incarnation) + 1) ~= incarnation .. '|' then
              return 0
            end
            redis.call('setex', KEYS[1], tonumber(ARGV[2]), ARGV[3])
            return 1
            """;

    private static final String DELETE_LEASE_SCRIPT = """
            local current = redis.call('get', KEYS[1])
            local incarnation = ARGV[1]
            if not current or string.sub(current, 1, string.len(incarnation) + 1) ~= incarnation .. '|' then
              return 0
            end
            return redis.call('del', KEYS[1])
            """;

    private final RedisClusterAsyncCommands<String, String> async;
    private final IRouteTable routeTable;
    private final String nodeIncarnation;
    private final List<NodeEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile NodeInformation self;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService scanExecutor;

    public RedisNodeDiscovery(RedisConfiguration redisConfig) {
        this(redisConfig, null, UUID.randomUUID().toString());
    }

    public RedisNodeDiscovery(RedisConfiguration redisConfig, IRouteTable routeTable) {
        this(redisConfig, routeTable, UUID.randomUUID().toString());
    }

    public RedisNodeDiscovery(RedisConfiguration redisConfig, IRouteTable routeTable, String nodeIncarnation) {
        this.async = redisConfig.async();
        this.routeTable = routeTable;
        this.nodeIncarnation = requireIdentity(nodeIncarnation, "nodeIncarnation");
        log.info("RedisNodeDiscovery initialized: incarnation={}", nodeIncarnation);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        heartbeatExecutor = IMExecutors.newScheduledExecutor("redis-node-heartbeat", 1);
        scanExecutor = IMExecutors.newScheduledExecutor("redis-node-scan", 1);
        if (self != null) doHeartbeat();
        heartbeatExecutor.scheduleAtFixedRate(this::doHeartbeat,
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
        scanExecutor.scheduleAtFixedRate(this::scanExpiredNodes,
                NODE_TTL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("RedisNodeDiscovery started (node={}, incarnation={}, heartbeat={}s, ttl={}s)",
                self != null ? self.getNodeId() : "null", nodeIncarnation,
                HEARTBEAT_INTERVAL_SEC, NODE_TTL_SEC);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (heartbeatExecutor != null) heartbeatExecutor.shutdown();
        if (scanExecutor != null) scanExecutor.shutdown();
        unregister();
        log.info("RedisNodeDiscovery stopped");
    }

    @Override
    public void register(NodeInformation node) {
        requireNodeId(node.getNodeId());
        self = node;
        try {
            async.setex(nodeKey(node.getNodeId()), NODE_TTL_SEC, serializeNodeLease(node, nodeIncarnation))
                    .get(3, TimeUnit.SECONDS);
            addToAliveSet(new NodeLease(node.getNodeId(), nodeIncarnation));
        } catch (Exception e) {
            throw new IllegalStateException("Redis node registration failed", e);
        }
        log.info("Node registered (redis): {}, incarnation={}", node, nodeIncarnation);
        notifyListeners(NodeEventType.NODE_ADDED, node);
    }

    @Override
    public void unregister() {
        NodeInformation leaving = self;
        if (leaving == null) return;
        NodeLease lease = new NodeLease(leaving.getNodeId(), nodeIncarnation);
        try {
            deleteLeaseIfCurrent(lease);
            removeFromAliveSet(lease);
            cleanupNodeRoutes(lease);
            log.info("Node unregistered (redis): {}, incarnation={}", leaving, nodeIncarnation);
        } catch (Exception e) {
            log.warn("Redis unregister failed: {}", e.getMessage());
        } finally {
            self = null;
        }
        notifyListeners(NodeEventType.NODE_REMOVED, leaving);
    }

    @Override
    public void heartbeat() {
        doHeartbeat();
    }

    @Override
    public List<NodeInformation> aliveNodes() {
        try {
            Set<String> members = async.smembers(KEY_ALIVE).get(3, TimeUnit.SECONDS);
            if (members == null || members.isEmpty()) return List.of();
            Map<String, NodeInformation> result = new LinkedHashMap<>();
            List<NodeLease> stale = new ArrayList<>();
            for (String member : members) {
                NodeLease candidate = parseAliveMember(member);
                if (candidate == null) {
                    async.srem(KEY_ALIVE, member);
                    continue;
                }
                NodeLeaseValue current = readLease(candidate.nodeId());
                if (current != null && candidate.incarnation().equals(current.incarnation())) {
                    result.put(candidate.nodeId(), current.node());
                } else {
                    stale.add(candidate);
                }
            }
            stale.forEach(this::cleanupStaleNode);
            return List.copyOf(result.values());
        } catch (Exception e) {
            log.warn("Redis aliveNodes failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public NodeInformation getNode(String nodeId) {
        try {
            NodeLeaseValue value = readLease(nodeId);
            return value != null ? value.node() : null;
        } catch (Exception e) {
            log.warn("Redis getNode failed for {}: {}", nodeId, e.getMessage());
            return null;
        }
    }

    @Override
    public void addListener(NodeEventListener listener) {
        listeners.add(listener);
    }

    private void doHeartbeat() {
        NodeInformation currentSelf = self;
        if (currentSelf == null) return;
        try {
            Number renewed = (Number) async.eval(RENEW_LEASE_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{nodeKey(currentSelf.getNodeId())}, nodeIncarnation,
                            String.valueOf(NODE_TTL_SEC), serializeNodeLease(currentSelf, nodeIncarnation))
                    .toCompletableFuture().join();
            if (renewed != null && renewed.longValue() == 1L) {
                addToAliveSet(new NodeLease(currentSelf.getNodeId(), nodeIncarnation));
            } else {
                log.warn("Node heartbeat rejected because lease was replaced: nodeId={}, incarnation={}",
                        currentSelf.getNodeId(), nodeIncarnation);
            }
        } catch (Exception e) {
            log.warn("Redis heartbeat failed: {}", e.getMessage());
        }
    }

    private void scanExpiredNodes() {
        try {
            Set<String> members = async.smembers(KEY_ALIVE).get(3, TimeUnit.SECONDS);
            if (members == null || members.isEmpty()) return;
            for (String member : members) {
                NodeLease candidate = parseAliveMember(member);
                if (candidate == null) {
                    async.srem(KEY_ALIVE, member);
                    continue;
                }
                NodeLeaseValue current = readLease(candidate.nodeId());
                long ttl = async.ttl(nodeKey(candidate.nodeId())).get(3, TimeUnit.SECONDS);
                if (current == null || !candidate.incarnation().equals(current.incarnation()) || ttl <= 0) {
                    log.warn("Node lease expired (redis): nodeId={}, incarnation={}",
                            candidate.nodeId(), candidate.incarnation());
                    cleanupStaleNode(candidate);
                }
            }
        } catch (Exception e) {
            log.warn("Redis scan failed: {}", e.getMessage());
        }
    }

    void cleanupStaleNode(String nodeId, String incarnation) {
        cleanupStaleNode(new NodeLease(nodeId, requireIdentity(incarnation, "incarnation")));
    }

    private void cleanupStaleNode(NodeLease lease) {
        try {
            deleteLeaseIfCurrent(lease);
            removeFromAliveSet(lease);
            cleanupNodeRoutes(lease);
            NodeLeaseValue replacement = readLease(lease.nodeId());
            if (replacement == null) {
                notifyListeners(NodeEventType.NODE_REMOVED,
                        new NodeInformation(lease.nodeId(), "unknown", 0));
            }
        } catch (Exception e) {
            log.warn("Redis cleanup failed for nodeId={}, incarnation={}: {}",
                    lease.nodeId(), lease.incarnation(), e.getMessage());
        }
    }

    private long deleteLeaseIfCurrent(NodeLease lease) {
        Number deleted = (Number) async.eval(DELETE_LEASE_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{nodeKey(lease.nodeId())}, lease.incarnation())
                .toCompletableFuture().join();
        return deleted != null ? deleted.longValue() : 0L;
    }

    private void addToAliveSet(NodeLease lease) throws Exception {
        async.sadd(KEY_ALIVE, aliveMember(lease)).get(3, TimeUnit.SECONDS);
    }

    private void removeFromAliveSet(NodeLease lease) {
        async.srem(KEY_ALIVE, aliveMember(lease)).toCompletableFuture().join();
    }

    private void cleanupNodeRoutes(NodeLease lease) {
        if (routeTable == null) return;
        int removed = routeTable.cleanupNodeRoutes(lease.nodeId(), lease.incarnation());
        if (removed > 0) {
            log.info("Cleaned routes for removed node lease: nodeId={}, incarnation={}, removed={}",
                    lease.nodeId(), lease.incarnation(), removed);
        }
    }

    private NodeLeaseValue readLease(String nodeId) throws Exception {
        String raw = async.get(nodeKey(nodeId)).get(3, TimeUnit.SECONDS);
        return deserializeNodeLease(raw, nodeId);
    }

    private void notifyListeners(NodeEventType type, NodeInformation node) {
        NodeEvent event = new NodeEvent(type, node);
        for (NodeEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("NodeEventListener error", e);
            }
        }
    }

    private static String nodeKey(String nodeId) {
        return KEY_NODE + "{" + nodeId + "}";
    }

    private static String aliveMember(NodeLease lease) {
        return lease.nodeId() + "|" + lease.incarnation();
    }

    private static NodeLease parseAliveMember(String member) {
        int separator = member != null ? member.lastIndexOf('|') : -1;
        if (separator <= 0 || separator == member.length() - 1) return null;
        return new NodeLease(member.substring(0, separator), member.substring(separator + 1));
    }

    private static String serializeNodeLease(NodeInformation node, String incarnation) {
        StringBuilder value = new StringBuilder(incarnation)
                .append('|').append(node.getNodeId())
                .append('|').append(node.getHost())
                .append('|').append(node.getPort());
        for (Map.Entry<String, String> entry : node.getAttrs().entrySet()) {
            value.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return value.toString();
    }

    private static NodeLeaseValue deserializeNodeLease(String value, String expectedNodeId) {
        if (value == null || value.isBlank()) return null;
        String[] parts = value.split("\\|", 5);
        if (parts.length < 4 || parts[0].isBlank() || !expectedNodeId.equals(parts[1])) {
            log.warn("Invalid node lease data for {}", expectedNodeId);
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            log.warn("Invalid port in node lease data for {}", expectedNodeId);
            return null;
        }
        Map<String, String> attrs = Collections.emptyMap();
        if (parts.length == 5 && !parts[4].isBlank()) {
            attrs = new LinkedHashMap<>();
            for (String attribute : parts[4].split("\\|")) {
                int equals = attribute.indexOf('=');
                if (equals > 0) attrs.put(attribute.substring(0, equals), attribute.substring(equals + 1));
            }
        }
        return new NodeLeaseValue(parts[0], new NodeInformation(parts[1], parts[2], port, attrs));
    }

    private static void requireNodeId(String nodeId) {
        requireIdentity(nodeId, "nodeId");
    }

    private static String requireIdentity(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no Redis delimiters");
        }
        return value;
    }

    private record NodeLease(String nodeId, String incarnation) { }

    private record NodeLeaseValue(String incarnation, NodeInformation node) { }
}
