package com.im.core.delivery;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.retry.RetryExecutor;
import com.im.common.util.IMExecutors;
import com.im.core.reliability.ReliableMessageHandler;
import com.im.api.SendMessageFailureStore;
import com.im.api.SendMessageIdempotency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 消息投递消费者（并行推送）。
 *
 * 从 "deliver" topic 消费消息，并行推送到目标用户所在的所有在线节点。
 *
 * 单聊：
 *   routeTable.lookupAllBindings(toUserId) → 按 session 路由并行推送
 *
 * 群聊：
 *   groupManager.getMemberIds(groupId) → 展开每个成员 → 并行推送
 *
 * 设计参考 OpenIM 的 DefaultAllNode.GetConnsAndOnlinePush：
 *   通过 IRouteTable.lookupAllBindings 精确路由，替代 OpenIM 的广播模式。
 */
public class DeliveryConsumer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final IMessageQueue messageQueue;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;
    private final String localNodeId;
    private final IGroupManager groupManager;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency idempotency;
    private final SendMessageFailureStore failureStore;

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
        this.retryExecutor = null;
        this.idempotency = null;
        this.failureStore = null;
        this.pusher = IMExecutors.newVirtualThreadExecutor("im-pusher");
    }

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId,
            IGroupManager groupManager,
            RetryExecutor retryExecutor,
            SendMessageIdempotency idempotency,
            SendMessageFailureStore failureStore) {
        this.messageQueue = messageQueue;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
        this.localNodeId = localNodeId;
        this.groupManager = groupManager;
        this.retryExecutor = retryExecutor;
        this.idempotency = idempotency;
        this.failureStore = failureStore;
        this.pusher = IMExecutors.newVirtualThreadExecutor("im-pusher");
    }

    @Override
    public void start() {
        IMessageQueue.MessageHandler delegate = msg -> {
            String groupId = msg.getGroupId();

            if (groupId != null) {
                // 群聊：展开成员
                handleGroupDelivery(msg, groupId);
            } else {
                // 单聊：直接路由
                String toUserId = msg.getToUserId();
                if (toUserId == null) {
                    log.warn("Delivery msg missing toUserId and groupId, seqId={}", msg.getSequenceId());
                    return;
                }
                handleSingleDelivery(msg, toUserId);
            }
        };

        this.handler = retryExecutor != null
                ? new ReliableMessageHandler(MessageQueueTopics.DELIVER, delegate,
                retryExecutor, idempotency, failureStore)
                : delegate;
        messageQueue.subscribe(MessageQueueTopics.DELIVER, handler);
        log.info("DeliveryConsumer subscribed to topic '{}'", MessageQueueTopics.DELIVER);
    }

    /**
     * 单聊投递：查路由，并行推。
     */
    private void handleSingleDelivery(Message msg, String toUserId) {
        List<RouteBinding> bindings = (routeTable != null)
                ? routeTable.lookupAllBindings(toUserId)
                : List.of();

        if (bindings.isEmpty()) {
            log.info("User {} offline, skip push for msg {}", toUserId, msg.getSequenceId());
            // TODO: 离线推送 — 用户不在线时通过 IOfflinePusher 推送通知栏消息
            // 类似微信/QQ 在手机通知栏弹出的消息提醒，需对接 FCM/APNs/Web Push 等三方服务
            return;
        }

        pushToBindings(msg, toUserId, bindings);
    }

    /**
     * 群聊投递：展开成员，逐个并行推。
     */
    private void handleGroupDelivery(Message msg, String groupId) {
        Set<String> memberIds = (groupManager != null)
                ? groupManager.getMemberIds(groupId)
                : Set.of();

        // 排除发送者自己（发送者已回 ACK，不需要再收一份）
        String fromUserId = msg.getFromUserId();
        if (fromUserId != null) {
            memberIds = memberIds.stream()
                    .filter(uid -> !uid.equals(fromUserId))
                    .collect(Collectors.toSet());
        }

        if (memberIds.isEmpty()) {
            log.info("Group {} has no members to deliver, msg {}", groupId, msg.getSequenceId());
            return;
        }

        // 给每个成员复制一份消息并设置 toUserId
        for (String memberId : memberIds) {
            Message copy = msg.copyForUser(memberId);
            List<RouteBinding> bindings = (routeTable != null)
                    ? routeTable.lookupAllBindings(memberId)
                    : List.of();
            if (bindings.isEmpty()) {
                log.info("Group member {} offline, skip push for msg {}", memberId, msg.getSequenceId());
            }
            pushToBindings(copy, memberId, bindings);
        }

        log.info("Group {}: delivering msg {} to {} members", groupId, msg.getSequenceId(), memberIds.size());
    }

    /**
     * 推送消息到指定路由绑定。
     * 本地节点和远端节点都按 session 精确投递。
     */
    private void pushToBindings(Message msg, String toUserId, List<RouteBinding> bindings) {
        List<Future<?>> futures = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (RouteBinding binding : bindings) {
            if (binding.isExpired(now)) {
                log.debug("Skip expired route binding: userId={}, platform={}, session={}, node={}",
                        toUserId, binding.platformId(), binding.sessionId(), binding.nodeId());
                routeTable.offline(toUserId, binding.nodeId(), binding.platformId(), binding.sessionId());
                continue;
            }
            RouteNode route = binding.toRouteNode(localNodeId);
            if (route.isLocal()) {
                futures.add(pusher.submit(() -> pushToLocalBinding(msg, toUserId, binding)));
            } else {
                futures.add(pusher.submit(() -> forwardToRemoteNode(msg, toUserId, route.getNodeId(), binding)));
            }
        }
        waitForPushes(futures);
    }

    private void waitForPushes(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("delivery interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("delivery push failed", e);
            }
        }
    }

    private void pushToLocalBinding(Message msg, String toUserId, RouteBinding binding) {
        List<IConnectionSession> sessions = sessionManager.getSessionsByUserId(toUserId);
        for (IConnectionSession session : sessions) {
            if (matches(binding, session) && session.getConnection().isActive()) {
                session.getConnection().write(msg);
                log.info("Pushed msg {} to user {} (local session {})",
                        msg.getSequenceId(), toUserId, session.getSessionId());
                return;
            }
        }
        log.warn("Local route found but no matching active session for user {}, platform={}, session={}, msg {}",
                toUserId, binding.platformId(), binding.sessionId(), msg.getSequenceId());
        routeTable.offline(toUserId, binding.nodeId(), binding.platformId(), binding.sessionId());
    }

    private boolean matches(RouteBinding binding, IConnectionSession session) {
        return binding.platformId() == session.getPlatformId()
                && binding.sessionId().equals(session.getSessionId());
    }

    private void forwardToRemoteNode(Message msg, String toUserId, String nodeId, RouteBinding binding) {
        if (clusterMessageBus != null) {
            ClusterMessage clusterMsg = ClusterMessage.fromMessage(localNodeId, msg, binding);
            boolean sent = clusterMessageBus.sendToNode(clusterMsg, nodeId);
            if (!sent) {
                throw new IllegalStateException("remote cluster delivery was not accepted by node " + nodeId);
            }
            log.info("Forwarded msg {} to remote node {} for user {} session {}",
                    msg.getSequenceId(), nodeId, toUserId, binding.sessionId());
        } else {
            throw new IllegalStateException("remote route " + nodeId
                    + " but no ClusterMessageBus, msg " + msg.getSequenceId() + " dropped");
        }
    }

    @Override
    public void stop() {
        if (handler != null) {
            messageQueue.unsubscribe(MessageQueueTopics.DELIVER, handler);
        }
        pusher.shutdown();
        log.info("DeliveryConsumer stopped");
    }
}
