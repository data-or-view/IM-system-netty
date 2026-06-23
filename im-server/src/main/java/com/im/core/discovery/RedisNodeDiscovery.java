package com.im.core.discovery;

import com.im.api.NodeInformation;
import com.im.api.INodeDiscovery;
import com.im.api.IRouteTable;
import com.im.common.util.IMExecutors;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Redis 集群节点发现（生产环境用）。
 *
 * <p>每个 IM 节点在 Redis 中注册为：</p>
 * <ul>
 *   <li><b>节点数据 key</b>: {@code im:node:{nodeId}}，值 = 序列化 NodeInformation，TTL= 30s</li>
 *   <li><b>活跃节点集合</b>: {@code im:nodes:alive}（Redis Set），存储所有活跃 nodeId</li>
 * </ul>
 *
 * <p>节点通过定时心跳（10s）刷新 TTL，Redis 自动清理过期 key。</p>
 */
public class RedisNodeDiscovery implements INodeDiscovery {

    private static final Logger log = LoggerFactory.getLogger(RedisNodeDiscovery.class);

    private static final String KEY_NODE = "im:node:";
    private static final String KEY_ALIVE = "im:nodes:alive";

    /** 节点注册 TTL（秒），超过此时间未心跳视为宕机 */
    private static final long NODE_TTL_SEC = 30;

    /** 心跳间隔（秒） */
    private static final long HEARTBEAT_INTERVAL_SEC = 10;

    private final RedisClusterAsyncCommands<String, String> async;
    private final IRouteTable routeTable;
    private final List<NodeEventListener> listeners = new CopyOnWriteArrayList<>();

    private volatile NodeInformation self;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService scanExecutor;

    public RedisNodeDiscovery(RedisConfiguration redisConfig) {
        this(redisConfig, null);
    }

    public RedisNodeDiscovery(RedisConfiguration redisConfig, IRouteTable routeTable) {
        this.async = redisConfig.async();
        this.routeTable = routeTable;
        log.info("RedisNodeDiscovery initialized");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        this.heartbeatExecutor = IMExecutors.newScheduledExecutor("redis-node-heartbeat", 1);
        this.scanExecutor = IMExecutors.newScheduledExecutor("redis-node-scan", 1);

        // 启动后立即执行一次心跳，然后按间隔执行
        if (self != null) {
            doHeartbeat();
        }
        heartbeatExecutor.scheduleAtFixedRate(this::doHeartbeat,
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        scanExecutor.scheduleAtFixedRate(this::scanExpiredNodes,
                NODE_TTL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        log.info("RedisNodeDiscovery started (node={}, heartbeat={}s, ttl={}s)",
                self != null ? self.getNodeId() : "null", HEARTBEAT_INTERVAL_SEC, NODE_TTL_SEC);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        if (scanExecutor != null) {
            scanExecutor.shutdown();
        }
        unregister();
        log.info("RedisNodeDiscovery stopped");
    }

    @Override
    public void register(NodeInformation node) {
        this.self = node;
        doHeartbeat();
        addToAliveSet(node.getNodeId());
        log.info("Node registered (redis): {}", node);
        notifyListeners(NodeEventListener.EventType.NODE_ADDED, node);
    }

    @Override
    public void unregister() {
        if (self == null) return;
        NodeInformation leaving = self;
        try {
            async.del(KEY_NODE + leaving.getNodeId()).get(3, TimeUnit.SECONDS);
            removeFromAliveSet(leaving.getNodeId());
            cleanupNodeRoutes(leaving.getNodeId());
            log.info("Node unregistered (redis): {}", leaving);
        } catch (Exception e) {
            log.warn("Redis unregister failed: {}", e.getMessage());
        }
        NodeInformation old = this.self;
        this.self = null;
        if (old != null) {
            notifyListeners(NodeEventListener.EventType.NODE_REMOVED, old);
        }
    }

    @Override
    public void heartbeat() {
        doHeartbeat();
    }

    @Override
    public List<NodeInformation> aliveNodes() {
        try {
            // 获取活跃节点集合
            Set<String> nodeIds = async.smembers(KEY_ALIVE).get(3, TimeUnit.SECONDS);
            if (nodeIds == null || nodeIds.isEmpty()) {
                return self != null ? List.of(self) : List.of();
            }

            List<NodeInformation> result = new ArrayList<>();
            List<String> staleIds = new ArrayList<>();

            for (String nodeId : nodeIds) {
                String val = async.get(KEY_NODE + nodeId).get(3, TimeUnit.SECONDS);
                if (val != null && !val.isEmpty()) {
                    NodeInformation node = deserializeNode(val, nodeId);
                    if (node != null) {
                        result.add(node);
                    } else {
                        // 反序列化失败，也标记为过期
                        staleIds.add(nodeId);
                    }
                } else {
                    // key 已过期但 Set 未清理
                    staleIds.add(nodeId);
                }
            }

            // 异步清理过期 nodeId
            if (!staleIds.isEmpty()) {
                cleanupStaleNodes(staleIds);
            }

            return result;
        } catch (Exception e) {
            log.warn("Redis aliveNodes failed: {} (fallback to self)", e.getMessage());
            return self != null ? List.of(self) : List.of();
        }
    }

    @Override
    public NodeInformation getNode(String nodeId) {
        if (self != null && self.getNodeId().equals(nodeId)) {
            return self;
        }
        try {
            String val = async.get(KEY_NODE + nodeId).get(3, TimeUnit.SECONDS);
            return val != null ? deserializeNode(val, nodeId) : null;
        } catch (Exception e) {
            log.warn("Redis getNode failed for {}: {}", nodeId, e.getMessage());
            return null;
        }
    }

    @Override
    public void addListener(NodeEventListener listener) {
        listeners.add(listener);
    }

    // ========== Heartbeat & Scan ==========

    private void doHeartbeat() {
        if (self == null) return;
        try {
            String key = KEY_NODE + self.getNodeId();
            String value = serializeNode(self);
            async.setex(key, NODE_TTL_SEC, value).get(3, TimeUnit.SECONDS);
            // 同时确保 Set 中存在
            addToAliveSet(self.getNodeId());
        } catch (Exception e) {
            log.warn("Redis heartbeat failed: {}", e.getMessage());
        }
    }

    private void scanExpiredNodes() {
        try {
            Set<String> nodeIds = async.smembers(KEY_ALIVE).get(3, TimeUnit.SECONDS);
            if (nodeIds == null || nodeIds.isEmpty()) return;

            List<String> staleIds = new ArrayList<>();
            for (String nodeId : nodeIds) {
                // 使用 TTL 判断是否存活
                long ttl = async.ttl(KEY_NODE + nodeId).get(3, TimeUnit.SECONDS);
                if (ttl <= 0) {
                    staleIds.add(nodeId);
                    log.warn("Node expired (redis): nodeId={}", nodeId);
                }
            }

            if (!staleIds.isEmpty()) {
                cleanupStaleNodes(staleIds);
            }
        } catch (Exception e) {
            log.warn("Redis scan failed: {}", e.getMessage());
        }
    }

    private void addToAliveSet(String nodeId) {
        try {
            async.sadd(KEY_ALIVE, nodeId).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis SADD failed: {}", e.getMessage());
        }
    }

    private void removeFromAliveSet(String nodeId) {
        try {
            async.srem(KEY_ALIVE, nodeId).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis SREM failed: {}", e.getMessage());
        }
    }

    private void cleanupStaleNodes(List<String> nodeIds) {
        try {
            for (String nodeId : nodeIds) {
                async.del(KEY_NODE + nodeId);
                async.srem(KEY_ALIVE, nodeId);
                cleanupNodeRoutes(nodeId);
            }
            // 通知监听器
            for (String nodeId : nodeIds) {
                // 构造一个最小 NodeInformation 用于通知
                NodeInformation staleNode = new NodeInformation(nodeId, "unknown", 0);
                notifyListeners(NodeEventListener.EventType.NODE_REMOVED, staleNode);
            }
        } catch (Exception e) {
            log.warn("Redis cleanup failed: {}", e.getMessage());
        }
    }

    private void cleanupNodeRoutes(String nodeId) {
        if (routeTable == null) {
            return;
        }
        try {
            int removed = routeTable.cleanupNodeRoutes(nodeId);
            if (removed > 0) {
                log.info("Cleaned routes for removed node: nodeId={}, removed={}", nodeId, removed);
            }
        } catch (Exception e) {
            log.warn("Route cleanup failed for removed node {}: {}", nodeId, e.getMessage());
        }
    }

    private void notifyListeners(NodeEventListener.EventType type, NodeInformation node) {
        NodeEventListener.Event event = new NodeEventListener.Event(type, node);
        for (NodeEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("NodeEventListener error", e);
            }
        }
    }

    // ========== Serialization ==========

    /**
     * 序列化 NodeInformation 为管道分隔字符串。
     * 格式: nodeId|host|port|key1=val1|key2=val2|...
     */
    private static String serializeNode(NodeInformation node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getNodeId()).append('|');
        sb.append(node.getHost()).append('|');
        sb.append(node.getPort());

        Map<String, String> attrs = node.getAttrs();
        if (attrs != null && !attrs.isEmpty()) {
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                sb.append('|');
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * 反序列化。格式: nodeId|host|port|k=v|...
     */
    private static NodeInformation deserializeNode(String val, String expectedNodeId) {
        if (val == null || val.isEmpty()) return null;
        String[] parts = val.split("\\|", 4);
        if (parts.length < 3) {
            log.warn("Invalid node data: {}", val);
            return null;
        }

        String nodeId = parts[0];
        // 验证 nodeId 匹配
        if (!nodeId.equals(expectedNodeId)) {
            log.warn("Node ID mismatch: expected={}, got={}", expectedNodeId, nodeId);
        }

        String host = parts[1];
        int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            log.warn("Invalid port in node data: {}", val);
            return null;
        }

        // 解析 attrs
        Map<String, String> attrs = Collections.emptyMap();
        if (parts.length > 3 && !parts[3].isEmpty()) {
            String[] attrParts = parts[3].split("\\|");
            attrs = new HashMap<>();
            for (String attr : attrParts) {
                int eq = attr.indexOf('=');
                if (eq > 0) {
                    attrs.put(attr.substring(0, eq), attr.substring(eq + 1));
                }
            }
        }

        return new NodeInformation(nodeId, host, port, attrs);
    }
}
