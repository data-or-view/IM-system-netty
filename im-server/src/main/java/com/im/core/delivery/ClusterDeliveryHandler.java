package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageHandler;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.Message;
import com.im.api.RouteBinding;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.MessageObservability;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群消息投递处理器 — 接收端。
 *
 * <p>当远端节点通过 {@link com.im.api.IClusterMessageBus} 转发消息到本节点时，
 * 此 handler 负责将消息推送到本节点上目标用户的 WebSocket 连接。</p>
 *
 * <p>对应 {@link DeliveryConsumer#pushToRoute} 中远程路由的接收端：
 * <pre>
 *   发送端 (node1)             接收端 (node2)
 *   DeliveryConsumer  ──→  ClusterMessageBus  ──→  ClusterDeliveryHandler
 *     routeTable.lookup()       sendToNode()          sessionManager → Channel
 * </pre>
 * </p>
 */
public class ClusterDeliveryHandler implements ClusterMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ClusterDeliveryHandler.class);

    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final String localNodeId;
    private final String localNodeIncarnation;

    public ClusterDeliveryHandler(ISessionManager sessionManager) {
        this(sessionManager, null, null, null);
    }

    public ClusterDeliveryHandler(ISessionManager sessionManager, IRouteTable routeTable, String localNodeId) {
        this(sessionManager, routeTable, localNodeId, null);
    }

    public ClusterDeliveryHandler(ISessionManager sessionManager, IRouteTable routeTable,
                                  String localNodeId, String localNodeIncarnation) {
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
        this.localNodeIncarnation = localNodeIncarnation;
    }

    @Override
    public void handle(ClusterMessage clusterMsg) {
        Message message = clusterMsg.getMessage();
        if (message == null) {
            log.warn("ClusterMessage missing message body, from={}", clusterMsg.getFromNodeId());
            return;
        }
        try (MessageObservability.Scope ignored = MessageObservability.bind("cluster.delivery", message)) {
            handleMessage(clusterMsg, message);
        }
    }

    private void handleMessage(ClusterMessage clusterMsg, Message message) {
        // 群聊消息：DeliveryConsumer 在发送前已通过 copyForUser() 设置了 toUserId
        // 单聊消息：toUserId 就是接收者
        String toUserId = message.getToUserId();
        if (toUserId == null) {
            Map<String, Object> fields = fields(clusterMsg, message);
            fields.put(LogFields.REASON, "missing_to_user");
            log.warn(StructuredLog.event(LogEvents.CLUSTER_HANDLER_FAILED, fields));
            return;
        }
        if (clusterMsg.hasTargetBinding()
                && (!clusterMsg.hasExactTargetBinding()
                || !clusterMsg.getTargetNodeIncarnation().equals(localNodeIncarnation))) {
            Map<String, Object> fields = fields(clusterMsg, message);
            fields.put(LogFields.REASON, "stale_or_incomplete_target_binding");
            log.warn(StructuredLog.event(LogEvents.CLUSTER_HANDLER_FAILED, fields));
            return;
        }
        log.info(StructuredLog.event(LogEvents.CLUSTER_DELIVERY_RECEIVED, fields(clusterMsg, message)));

        // 查找本节点上该用户的所有活跃 session（多端在线）
        List<IConnectionSession> sessions = sessionManager.getSessionsByUserId(toUserId);
        if (sessions.isEmpty()) {
            if (clusterMsg.hasTargetBinding()) {
                removeStaleTargetRoute(toUserId, clusterMsg);
            }
            Map<String, Object> fields = fields(clusterMsg, message);
            fields.put(LogFields.TO_USER_ID, toUserId);
            log.info(StructuredLog.event(LogEvents.CLUSTER_DELIVERY_NO_LOCAL_SESSION, fields));
            return;
        }

        int delivered = 0;
        for (IConnectionSession session : sessions) {
            if (matchesTarget(clusterMsg, session) && session.getConnection().isActive()) {
                session.getConnection().write(message);
                delivered++;
                Map<String, Object> fields = fields(clusterMsg, message);
                fields.put(LogFields.SESSION_ID, session.getSessionId());
                fields.put(LogFields.PLATFORM_ID, session.getPlatformId());
                log.info(StructuredLog.event(LogEvents.CLUSTER_DELIVERY_LOCAL_SUCCEEDED, fields));
            }
        }

        if (delivered == 0 && clusterMsg.hasTargetBinding()) {
            removeStaleTargetRoute(toUserId, clusterMsg);
        }

        Map<String, Object> fields = fields(clusterMsg, message);
        fields.put(LogFields.DELIVERED_COUNT, delivered);
        fields.put("sessionCount", sessions.size());
        log.debug(StructuredLog.event(LogEvents.CLUSTER_DELIVERY_LOCAL_SUCCEEDED, fields));
    }

    private boolean matchesTarget(ClusterMessage clusterMsg, IConnectionSession session) {
        if (!clusterMsg.hasTargetBinding()) {
            return true;
        }
        return clusterMsg.getTargetPlatformId() == session.getPlatformId()
                && clusterMsg.getTargetSessionId().equals(session.getSessionId());
    }

    private void removeStaleTargetRoute(String userId, ClusterMessage clusterMsg) {
        if (routeTable == null || localNodeId == null || !clusterMsg.hasExactTargetBinding()) {
            return;
        }
        routeTable.offline(new RouteBinding(userId, localNodeId, clusterMsg.getTargetPlatformId(),
                clusterMsg.getTargetSessionId(), 0, clusterMsg.getTargetNodeIncarnation(),
                clusterMsg.getTargetGeneration()));
        Map<String, Object> fields = fields(clusterMsg, clusterMsg.getMessage());
        fields.put(LogFields.TO_USER_ID, userId);
        fields.put(LogFields.PLATFORM_ID, clusterMsg.getTargetPlatformId());
        fields.put(LogFields.SESSION_ID, clusterMsg.getTargetSessionId());
        log.warn(StructuredLog.event(LogEvents.CLUSTER_STALE_ROUTE_REMOVED, fields));
    }

    private Map<String, Object> fields(ClusterMessage clusterMsg, Message message) {
        Map<String, Object> fields = new LinkedHashMap<>(MessageObservability.fields("cluster.delivery", message));
        fields.put(LogFields.SOURCE_NODE_ID, clusterMsg.getFromNodeId());
        fields.put(LogFields.NODE_ID, localNodeId);
        if (clusterMsg.hasTargetBinding()) {
            fields.put(LogFields.PLATFORM_ID, clusterMsg.getTargetPlatformId());
            fields.put(LogFields.SESSION_ID, clusterMsg.getTargetSessionId());
        }
        return fields;
    }
}
