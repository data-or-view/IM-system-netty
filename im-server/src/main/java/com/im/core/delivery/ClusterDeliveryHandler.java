package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageHandler;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    public ClusterDeliveryHandler(ISessionManager sessionManager) {
        this(sessionManager, null, null);
    }

    public ClusterDeliveryHandler(ISessionManager sessionManager, IRouteTable routeTable, String localNodeId) {
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
    }

    @Override
    public void handle(ClusterMessage clusterMsg) {
        Message message = clusterMsg.getMessage();
        if (message == null) {
            log.warn("ClusterMessage missing message body, from={}", clusterMsg.getFromNodeId());
            return;
        }

        // 群聊消息：DeliveryConsumer 在发送前已通过 copyForUser() 设置了 toUserId
        // 单聊消息：toUserId 就是接收者
        String toUserId = message.getToUserId();
        if (toUserId == null) {
            log.warn("ClusterMessage missing toUserId, from={}, seq={}",
                    clusterMsg.getFromNodeId(), message.getSequenceId());
            return;
        }

        // 查找本节点上该用户的所有活跃 session（多端在线）
        List<IConnectionSession> sessions = sessionManager.getSessionsByUserId(toUserId);
        if (sessions.isEmpty()) {
            log.info("User {} not connected to this node, skip cluster delivery, msg seq={}",
                    toUserId, message.getSequenceId());
            return;
        }

        int delivered = 0;
        for (IConnectionSession session : sessions) {
            if (matchesTarget(clusterMsg, session) && session.getConnection().isActive()) {
                session.getConnection().write(message);
                delivered++;
                log.debug("Cluster-delivered msg {} to user {} session {}",
                        message.getSequenceId(), toUserId, session.getSessionId());
            }
        }

        if (delivered == 0 && clusterMsg.hasTargetBinding()) {
            removeStaleTargetRoute(toUserId, clusterMsg);
        }

        log.info("Cluster-delivered msg {} to user {} ({} matched of {} sessions on this node)",
                message.getSequenceId(), toUserId, delivered, sessions.size());
    }

    private boolean matchesTarget(ClusterMessage clusterMsg, IConnectionSession session) {
        if (!clusterMsg.hasTargetBinding()) {
            return true;
        }
        return clusterMsg.getTargetPlatformId() == session.getPlatformId()
                && clusterMsg.getTargetSessionId().equals(session.getSessionId());
    }

    private void removeStaleTargetRoute(String userId, ClusterMessage clusterMsg) {
        if (routeTable == null || localNodeId == null || clusterMsg.getTargetPlatformId() == null) {
            return;
        }
        routeTable.offline(userId, localNodeId, clusterMsg.getTargetPlatformId(), clusterMsg.getTargetSessionId());
        log.warn("Removed stale cluster target route: userId={}, node={}, platform={}, session={}",
                userId, localNodeId, clusterMsg.getTargetPlatformId(), clusterMsg.getTargetSessionId());
    }
}
