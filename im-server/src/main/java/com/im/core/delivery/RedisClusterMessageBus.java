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
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.MessageObservability;
import com.im.core.observability.StructuredLog;
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
import java.util.concurrent.TimeUnit;

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
    private static final long PUBLISH_TIMEOUT_SECONDS = 3;
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_FROM_NODE_ID = "fromNodeId";
    private static final String FIELD_TTL = "ttl";
    private static final String FIELD_COMMAND = "command";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_TARGET_PLATFORM_ID = "targetPlatformId";
    private static final String FIELD_TARGET_SESSION_ID = "targetSessionId";
    private static final String FIELD_TARGET_NODE_INCARNATION = "targetNodeIncarnation";
    private static final String FIELD_TARGET_GENERATION = "targetGeneration";
    private static final String FIELD_COMMAND_TYPE = "type";
    private static final String FIELD_COMMAND_USER_ID = "userId";
    private static final String FIELD_COMMAND_PLATFORM_ID = "platformId";
    private static final String FIELD_COMMAND_SESSION_ID = "sessionId";
    private static final String FIELD_COMMAND_NODE_INCARNATION = "nodeIncarnation";
    private static final String FIELD_COMMAND_GENERATION = "generation";
    private static final String FIELD_COMMAND_REASON = "reason";
    private static final String FIELD_COMMAND_PAYLOAD = "payload";

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
    public boolean sendToNode(ClusterMessage msg, String targetNodeId) {
        if (nodeId.equals(targetNodeId)) {
            log.debug("Skipping sendToNode self: {}", targetNodeId);
            return true;
        }
        try {
            String json = serialize(msg);
            String channel = nodeChannel(targetNodeId);
            Long receivers = pubSubAsync.publish(channel, json).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (receivers == null || receivers <= 0) {
                Map<String, Object> fields = clusterFields(msg, targetNodeId);
                fields.put("channel", channel);
                log.warn(StructuredLog.event(LogEvents.CLUSTER_MESSAGE_NO_SUBSCRIBER, fields));
                return false;
            }
            Map<String, Object> fields = clusterFields(msg, targetNodeId);
            fields.put("channel", channel);
            fields.put("receivers", receivers);
            log.info(StructuredLog.event(LogEvents.CLUSTER_MESSAGE_PUBLISH_SUCCEEDED, fields));
            return true;
        } catch (Exception e) {
            Map<String, Object> fields = clusterFields(msg, targetNodeId);
            fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
            log.error(StructuredLog.event(LogEvents.MESSAGE_FORWARD_REMOTE_FAILED, fields), e);
            throw new IllegalStateException("failed to send cluster message to node " + targetNodeId, e);
        }
    }

    @Override
    public void broadcast(ClusterMessage msg) {
        if (nodeId == null) return;
        try {
            String json = serialize(msg);
            pubSubAsync.publish(BROADCAST_CHANNEL, json);
            Map<String, Object> fields = clusterFields(msg, null);
            fields.put("channel", BROADCAST_CHANNEL);
            log.debug(StructuredLog.event(LogEvents.CLUSTER_MESSAGE_PUBLISH_SUCCEEDED, fields));
        } catch (Exception e) {
            Map<String, Object> fields = clusterFields(msg, null);
            fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
            log.error(StructuredLog.event(LogEvents.MESSAGE_FORWARD_REMOTE_FAILED, fields), e);
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
                    Map<String, Object> fields = clusterFields(msg, nodeId);
                    fields.put(LogFields.REASON, "ttl_exhausted");
                    log.warn(StructuredLog.event(LogEvents.CLUSTER_HANDLER_FAILED, fields));
                    return;
                }

                // 按 topic 分派
                String topic = msg.getTopic();
                try (MessageObservability.Scope ignored = MessageObservability.bind("cluster." + topic, msg.getMessage())) {
                    log.debug(StructuredLog.event(LogEvents.CLUSTER_MESSAGE_RECEIVED,
                            clusterFields(msg, nodeId)));
                List<ClusterMessageHandler> handlers = handlerRegistry.get(topic);
                if (handlers == null || handlers.isEmpty()) {
                    log.debug("No handlers for topic={}, channel={}", topic, channel);
                    return;
                }
                for (ClusterMessageHandler handler : handlers) {
                    try {
                        handler.handle(msg);
                    } catch (Exception e) {
                        Map<String, Object> fields = clusterFields(msg, nodeId);
                        fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
                        log.warn(StructuredLog.event(LogEvents.CLUSTER_HANDLER_FAILED, fields));
                    }
                }
                }
            } catch (Exception e) {
                log.warn(StructuredLog.event(LogEvents.CLUSTER_HANDLER_FAILED,
                        "channel", channel,
                        LogFields.NODE_ID, nodeId,
                        LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()));
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
        root.put(FIELD_KIND, msg.getKind().name());
        root.put(FIELD_FROM_NODE_ID, msg.getFromNodeId());
        root.put(FIELD_TTL, msg.getTtl());
        if (msg.getKind() == ClusterMessageKind.CLUSTER_COMMAND) {
            root.put(FIELD_COMMAND, commandToMap(msg.getCommand()));
        } else {
            Message message = msg.getMessage();
            root.put(FIELD_MESSAGE, message.toJsonMap());
            if (msg.hasTargetBinding()) {
                root.put(FIELD_TARGET_PLATFORM_ID, msg.getTargetPlatformId());
                root.put(FIELD_TARGET_SESSION_ID, msg.getTargetSessionId());
                root.put(FIELD_TARGET_NODE_INCARNATION, msg.getTargetNodeIncarnation());
                root.put(FIELD_TARGET_GENERATION, msg.getTargetGeneration());
            }
        }

        return MAPPER.writeValueAsString(root);
    }

    /**
     * 反序列化 JSON 为 ClusterMessage。
     */
    ClusterMessage deserialize(String json) throws Exception {
        Map<String, Object> root = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});

        String kindStr = (String) root.get(FIELD_KIND);
        String fromNodeId = (String) root.get(FIELD_FROM_NODE_ID);
        int ttl = ((Number) root.get(FIELD_TTL)).intValue();

        ClusterMessageKind kind = ClusterMessageKind.valueOf(kindStr);
        if (kind == ClusterMessageKind.CLUSTER_COMMAND) {
            @SuppressWarnings("unchecked")
            Map<String, Object> commandMap = (Map<String, Object>) root.get(FIELD_COMMAND);
            return new ClusterMessage(kind, fromNodeId, commandFromMap(commandMap), ttl);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> msgMap = (Map<String, Object>) root.get(FIELD_MESSAGE);
        Integer targetPlatformId = root.containsKey(FIELD_TARGET_PLATFORM_ID)
                ? ((Number) root.get(FIELD_TARGET_PLATFORM_ID)).intValue()
                : null;
        String targetSessionId = (String) root.get(FIELD_TARGET_SESSION_ID);
        String targetNodeIncarnation = (String) root.get(FIELD_TARGET_NODE_INCARNATION);
        String targetGeneration = (String) root.get(FIELD_TARGET_GENERATION);
        return new ClusterMessage(kind, fromNodeId, Message.fromJsonMap(msgMap),
                targetPlatformId, targetSessionId, targetNodeIncarnation, targetGeneration, ttl);
    }

    private static Map<String, Object> commandToMap(ClusterCommand command) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(FIELD_COMMAND_TYPE, command.type().name());
        map.put(FIELD_COMMAND_USER_ID, command.userId());
        map.put(FIELD_COMMAND_PLATFORM_ID, command.platformId());
        map.put(FIELD_COMMAND_SESSION_ID, command.sessionId());
        map.put(FIELD_COMMAND_NODE_INCARNATION, command.nodeIncarnation());
        map.put(FIELD_COMMAND_GENERATION, command.generation());
        map.put(FIELD_COMMAND_REASON, command.reason());
        map.put(FIELD_COMMAND_PAYLOAD, command.payload());
        return map;
    }

    private static ClusterCommand commandFromMap(Map<String, Object> map) {
        ClusterCommandType type = ClusterCommandType.valueOf((String) map.get(FIELD_COMMAND_TYPE));
        String userId = (String) map.get(FIELD_COMMAND_USER_ID);
        int platformId = ((Number) map.getOrDefault(FIELD_COMMAND_PLATFORM_ID,
                ClusterCommand.ANY_PLATFORM_ID)).intValue();
        String sessionId = (String) map.getOrDefault(FIELD_COMMAND_SESSION_ID,
                ClusterCommand.DEFAULT_SESSION_ID);
        String nodeIncarnation = (String) map.get(FIELD_COMMAND_NODE_INCARNATION);
        String generation = (String) map.get(FIELD_COMMAND_GENERATION);
        String reason = (String) map.get(FIELD_COMMAND_REASON);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.getOrDefault(FIELD_COMMAND_PAYLOAD, Map.of());
        return new ClusterCommand(type, userId, platformId, sessionId,
                nodeIncarnation, generation, reason, payload);
    }

    // ── 工具 ──

    private static String nodeChannel(String nodeId) {
        return NODE_CHANNEL_PREFIX + nodeId + NODE_CHANNEL_SUFFIX;
    }

    private Map<String, Object> clusterFields(ClusterMessage msg, String targetNodeId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (msg == null) {
            return fields;
        }
        if (msg.getMessage() != null) {
            fields.putAll(MessageObservability.fields(msg.getTopic(), msg.getMessage()));
        }
        fields.put(LogFields.TOPIC, msg.getTopic());
        fields.put("kind", msg.getKind());
        fields.put(LogFields.NODE_ID, nodeId);
        fields.put(LogFields.SOURCE_NODE_ID, msg.getFromNodeId());
        fields.put(LogFields.TARGET_NODE_ID, targetNodeId);
        fields.put("ttl", msg.getTtl());
        if (msg.getCommand() != null) {
            fields.put(LogFields.USER_ID, msg.getCommand().userId());
            fields.put(LogFields.PLATFORM_ID, msg.getCommand().platformId());
            fields.put(LogFields.SESSION_ID, msg.getCommand().sessionId());
            fields.put(LogFields.REASON, msg.getCommand().reason());
            fields.put("commandType", msg.getCommand().type());
        }
        return fields;
    }
}
