package com.im.core.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ClusterCommand;
import com.im.api.ClusterCommandType;
import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageKind;
import com.im.api.ClusterMessageHandler;
import com.im.api.IClusterMessageBus;
import com.im.api.Message;
import com.im.common.util.IMExecutors;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.core.redis.RedisConfiguration;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Redis Pub/Sub 集群消息总线。
 *
 * <p>使用 Redis pub/sub 实现跨节点消息转发：</p>
 * <ul>
 *   <li>{@code sendToNode} → 发布到 {@code im:node:{targetNodeId}:msgs}</li>
 *   <li>{@code broadcast} → 发布到 {@code im:bus:broadcast}</li>
 *   <li>每个节点订阅自身节点消息频道 + 广播频道</li>
 * </ul>
 *
 * <p>消息序列化使用 JSON。在 Redis 单机或集群模式下均可用。</p>
 */
public class RedisClusterMessageBus implements IClusterMessageBus {

    private static final Logger log = LoggerFactory.getLogger(RedisClusterMessageBus.class);

    private static final String BROADCAST_CHANNEL = "im:bus:broadcast";
    private static final String NODE_CHANNEL_PREFIX = "im:node:";
    private static final String NODE_CHANNEL_SUFFIX = ":msgs";

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final RedisConfiguration redisConfig;

    private final String nodeId;

    /** topic → 订阅者列表 */
    private final ConcurrentHashMap<String, List<ClusterMessageHandler>> handlerRegistry = new ConcurrentHashMap<>();

    /** 反序列化线程池 */
    private final ExecutorService dispatchExecutor;

    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    private RedisPubSubAsyncCommands<String, String> pubSubAsync;

    public RedisClusterMessageBus(RedisConfiguration redisConfig, String nodeId) {
        this.redisConfig = redisConfig;
        this.nodeId = nodeId;
        this.dispatchExecutor = IMExecutors.newVirtualThreadExecutor("redis-bus");
    }

    @Override
    public void start() {
        pubSubConnection = redisConfig.createPubSubConnection();

        pubSubAsync = pubSubConnection.async();

        pubSubConnection.addListener(new RedisPubSubListener<String, String>() {
            @Override
            public void message(String channel, String message) {
                dispatchMessage(channel, message);
            }
            @Override public void message(String pattern, String channel, String message) {}
            @Override public void subscribed(String channel, long count) {}
            @Override public void psubscribed(String pattern, long count) {}
            @Override public void unsubscribed(String channel, long count) {}
            @Override public void punsubscribed(String pattern, long count) {}
        });

        // 订阅广播频道 + 本节点专属频道
        String nodeChannel = nodeChannel(nodeId);
        pubSubAsync.subscribe(BROADCAST_CHANNEL, nodeChannel);
        log.info("RedisClusterMessageBus started: node={}, subscribed={}, {}",
                nodeId, BROADCAST_CHANNEL, nodeChannel);
    }

    @Override
    public void stop() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        dispatchExecutor.shutdown();
        log.info("RedisClusterMessageBus stopped: node={}", nodeId);
    }

    // ── 发送 ──

    @Override
    public void sendToNode(ClusterMessage msg, String targetNodeId) {
        if (nodeId.equals(targetNodeId)) {
            log.debug("Skipping sendToNode self: {}", targetNodeId);
            return;
        }
        try {
            String json = serialize(msg);
            String channel = nodeChannel(targetNodeId);
            pubSubAsync.publish(channel, json);
            log.debug("Sent to node {}: topic={}, kind={}", targetNodeId, msg.getTopic(), msg.getKind());
        } catch (Exception e) {
            log.error("Failed to send to node {}: {}", targetNodeId, e.getMessage());
        }
    }

    @Override
    public void broadcast(ClusterMessage msg) {
        if (nodeId == null) return;
        try {
            String json = serialize(msg);
            pubSubAsync.publish(BROADCAST_CHANNEL, json);
            log.debug("Broadcast: topic={}, kind={}", msg.getTopic(), msg.getKind());
        } catch (Exception e) {
            log.error("Failed to broadcast: {}", e.getMessage());
        }
    }

    // ── 订阅 ──

    @Override
    public void subscribe(String topic, ClusterMessageHandler handler) {
        handlerRegistry.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.debug("Handler subscribed: topic={}", topic);
    }

    @Override
    public void unsubscribe(String topic, ClusterMessageHandler handler) {
        List<ClusterMessageHandler> handlers = handlerRegistry.get(topic);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                handlerRegistry.remove(topic);
            }
        }
    }

    // ── 消息接收与分派 ──

    private void dispatchMessage(String channel, String json) {
        dispatchExecutor.execute(() -> {
            try {
                ClusterMessage msg = deserialize(json);
                if (msg == null) return;

                // 防环路：跳过自己发出的消息
                if (nodeId.equals(msg.getFromNodeId())) return;

                // TTL 递减
                if (!msg.decrementTtl()) {
                    log.warn("Dropped message: TTL exhausted, from={}, topic={}",
                            msg.getFromNodeId(), msg.getTopic());
                    return;
                }

                // 按 topic 分派
                String topic = msg.getTopic();
                List<ClusterMessageHandler> handlers = handlerRegistry.get(topic);
                if (handlers == null || handlers.isEmpty()) {
                    log.debug("No handlers for topic={}, channel={}", topic, channel);
                    return;
                }
                for (ClusterMessageHandler handler : handlers) {
                    try {
                        handler.handle(msg);
                    } catch (Exception e) {
                        log.warn("Handler error for topic={}: {}", topic, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to process cluster message from channel={}: {}", channel, e.getMessage());
            }
        });
    }

    // ── JSON 序列化 ──

    /**
     * 序列化 ClusterMessage 为 JSON。
     *
     * 格式：
     * {"kind":"USER_MESSAGE","fromNodeId":"n1","ttl":3,
     *  "command":{"_op":10,"_seq":1,...,"_body":"base64..."}}
     */
    String serialize(ClusterMessage msg) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", msg.getKind().name());
        root.put("fromNodeId", msg.getFromNodeId());
        root.put("ttl", msg.getTtl());
        if (msg.getKind() == ClusterMessageKind.CLUSTER_COMMAND) {
            root.put("command", commandToMap(msg.getCommand()));
        } else {
            Message message = msg.getMessage();
            root.put("message", message.toJsonMap());
        }

        return MAPPER.writeValueAsString(root);
    }

    /**
     * 反序列化 JSON 为 ClusterMessage。
     */
    ClusterMessage deserialize(String json) throws Exception {
        Map<String, Object> root = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});

        String kindStr = (String) root.get("kind");
        String fromNodeId = (String) root.get("fromNodeId");
        int ttl = ((Number) root.get("ttl")).intValue();

        ClusterMessageKind kind = ClusterMessageKind.valueOf(kindStr);
        if (kind == ClusterMessageKind.CLUSTER_COMMAND) {
            @SuppressWarnings("unchecked")
            Map<String, Object> commandMap = (Map<String, Object>) root.get("command");
            return new ClusterMessage(kind, fromNodeId, commandFromMap(commandMap), ttl);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> msgMap = (Map<String, Object>) root.get("message");
        return new ClusterMessage(kind, fromNodeId, Message.fromJsonMap(msgMap), ttl);
    }

    private static Map<String, Object> commandToMap(ClusterCommand command) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", command.type().name());
        map.put("userId", command.userId());
        map.put("platformId", command.platformId());
        map.put("sessionId", command.sessionId());
        map.put("reason", command.reason());
        map.put("payload", command.payload());
        return map;
    }

    private static ClusterCommand commandFromMap(Map<String, Object> map) {
        ClusterCommandType type = ClusterCommandType.valueOf((String) map.get("type"));
        String userId = (String) map.get("userId");
        int platformId = ((Number) map.getOrDefault("platformId", -1)).intValue();
        String sessionId = (String) map.getOrDefault("sessionId", "default");
        String reason = (String) map.get("reason");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.getOrDefault("payload", Map.of());
        return new ClusterCommand(type, userId, platformId, sessionId, reason, payload);
    }

    // ── 工具 ──

    private static String nodeChannel(String nodeId) {
        return NODE_CHANNEL_PREFIX + nodeId + NODE_CHANNEL_SUFFIX;
    }
}
