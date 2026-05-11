package com.im.core.delivery;

import com.im.api.*;
import com.im.core.util.IMExecutors;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 消息投递消费者（并行推送）。
 *
 * 从 "deliver" topic 消费消息，并行推送到目标用户所在的所有在线节点。
 *
 * 单聊：
 *   routeTable.lookupAll(toUserId) → 并行推送到所有在线节点
 *
 * 群聊：
 *   groupManager.getMemberIds(groupId) → 展开每个成员 → 并行推送
 *
 * 设计参考 OpenIM 的 DefaultAllNode.GetConnsAndOnlinePush：
 *   通过 IRouteTable.lookupAll 精确路由，替代 OpenIM 的广播模式。
 */
public class DeliveryConsumer implements ILifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final IMessageQueue messageQueue;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;
    private final String localNodeId;
    private final IGroupManager groupManager;

    /** 并行推送执行器（虚拟线程，每个路由一条） */
    private final ExecutorService pusher;

    private volatile IMessageQueue.MessageHandler handler;

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId) {
        this(messageQueue, sessionManager, routeTable, clusterMessageBus, localNodeId, null);
    }

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId,
            IGroupManager groupManager) {
        this.messageQueue = messageQueue;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
        this.localNodeId = localNodeId;
        this.groupManager = groupManager;
        this.pusher = IMExecutors.newVirtualThreadExecutor("im-pusher");
    }

    @Override
    public void start() {
        this.handler = msg -> {
            String groupId = msg.getHeader("groupId");

            if (groupId != null) {
                // 群聊：展开成员
                handleGroupDelivery(msg, groupId);
            } else {
                // 单聊：直接路由
                String toUserId = msg.getHeader("toUserId");
                if (toUserId == null) {
                    log.warn("Delivery msg missing toUserId and groupId, seqId={}", msg.getSeqId());
                    return;
                }
                handleSingleDelivery(msg, toUserId);
            }
        };

        messageQueue.subscribe(MessageQueueTopics.DELIVER, handler);
        log.info("DeliveryConsumer subscribed to topic '{}'", MessageQueueTopics.DELIVER);
    }

    /**
     * 单聊投递：查路由，并行推。
     */
    private void handleSingleDelivery(IMCommand msg, String toUserId) {
        List<RouteNode> routes = (routeTable != null)
                ? routeTable.lookupAll(toUserId)
                : List.of();

        if (routes.isEmpty()) {
            log.info("User {} offline, skip push for msg {}", toUserId, msg.getSeqId());
            return;
        }

        for (RouteNode route : routes) {
            pusher.execute(() -> pushToRoute(msg, toUserId, route));
        }
    }

    /**
     * 群聊投递：展开成员，逐个并行推。
     */
    private void handleGroupDelivery(IMCommand msg, String groupId) {
        Set<String> memberIds = (groupManager != null)
                ? groupManager.getMemberIds(groupId)
                : Set.of();

        // 排除发送者自己（发送者已回 ACK，不需要再收一份）
        String fromUserId = msg.getHeader("fromUserId");
        if (fromUserId != null) {
            memberIds = memberIds.stream()
                    .filter(uid -> !uid.equals(fromUserId))
                    .collect(java.util.stream.Collectors.toSet());
        }

        if (memberIds.isEmpty()) {
            log.info("Group {} has no members to deliver, msg {}", groupId, msg.getSeqId());
            return;
        }

        // 给每个成员复制一份消息并设置 toUserId
        for (String memberId : memberIds) {
            IMCommand copy = copyForUser(msg, memberId);
            pusher.execute(() -> {
                List<RouteNode> routes = (routeTable != null)
                        ? routeTable.lookupAll(memberId)
                        : List.of();
                if (routes.isEmpty()) {
                    log.info("Group member {} offline, skip push for msg {}", memberId, msg.getSeqId());
                }
                for (RouteNode route : routes) {
                    pushToRoute(copy, memberId, route);
                }
            });
        }

        log.info("Group {}: delivering msg {} to {} members", groupId, msg.getSeqId(), memberIds.size());
    }

    /**
     * 复制消息并设置 toUserId。
     * 避免多成员共享同一个 IMCommand 实例导致并发修改。
     */
    private IMCommand copyForUser(IMCommand original, String userId) {
        IMCommand copy = new IMCommand(original.getType());
        copy.setSeqId(original.getSeqId());
        copy.setMessageId(original.getMessageId());
        copy.setTimestamp(original.getTimestamp());
        copy.putHeader("fromUserId", original.getHeader("fromUserId"));
        copy.putHeader("groupId", original.getHeader("groupId"));
        copy.putHeader("conversationId", original.getHeader("conversationId"));
        copy.putHeader("_ms", original.getHeader("_ms"));
        copy.putHeader("_ct", original.getHeader("_ct"));
        copy.putHeader("toUserId", userId);
        copy.setBody(original.getBody() != null ? original.getBody().clone() : null);
        return copy;
    }

    /**
     * 推送消息到指定路由节点。
     * 多端在线时，本地节点推送到该用户的所有活跃 session。
     */
    private void pushToRoute(IMCommand msg, String toUserId, RouteNode route) {
        if (route.isLocal()) {
            // 推送所有在线端（多端登录）
            List<IConnectionSession> sessions = sessionManager.getSessionsByUserId(toUserId);
            if (sessions.isEmpty()) {
                log.warn("Local route found but no active session for user {}, msg {}",
                        toUserId, msg.getSeqId());
                return;
            }
            for (IConnectionSession session : sessions) {
                Channel ch = session.getChannel();
                if (ch != null && ch.isActive()) {
                    ch.writeAndFlush(msg);
                }
            }
            log.info("Pushed msg {} to user {} (local, {} sessions)", msg.getSeqId(), toUserId, sessions.size());
        } else {
            if (clusterMessageBus != null) {
                ClusterMessage clusterMsg = ClusterMessage.fromIMCommand(localNodeId, msg);
                clusterMessageBus.sendToNode(clusterMsg, route.getNodeId());
                log.info("Forwarded msg {} to remote node {} for user {}",
                        msg.getSeqId(), route.getNodeId(), toUserId);
            } else {
                log.warn("Remote route {} but no ClusterMessageBus, msg {} dropped",
                        route.getNodeId(), msg.getSeqId());
            }
        }
    }

    @Override
    public void shutdown() {
        if (handler != null) {
            messageQueue.unsubscribe(MessageQueueTopics.DELIVER, handler);
        }
        pusher.shutdown();
        log.info("DeliveryConsumer stopped");
    }
}
