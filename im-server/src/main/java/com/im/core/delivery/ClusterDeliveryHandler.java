package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageHandler;
import com.im.api.IConnectionSession;
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

    public ClusterDeliveryHandler(ISessionManager sessionManager) {
        this.sessionManager = sessionManager;
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

        for (IConnectionSession session : sessions) {
            if (session.getConnection().isActive()) {
                session.getConnection().write(message);
                log.debug("Cluster-delivered msg {} to user {} session {}",
                        message.getSequenceId(), toUserId, session.getSessionId());
            }
        }

        log.info("Cluster-delivered msg {} to user {} ({} sessions on this node)",
                message.getSequenceId(), toUserId, sessions.size());
    }
}
