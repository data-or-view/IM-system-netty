package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IGroupManager;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.Message;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.common.util.IMExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Resolves message routes and pushes messages locally or through the cluster bus.
 */
final class MessageDeliveryWorkflow {

    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryWorkflow.class);

    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;
    private final String localNodeId;
    private final IGroupManager groupManager;
    private final ExecutorService pusher;

    MessageDeliveryWorkflow(ISessionManager sessionManager,
                            IRouteTable routeTable,
                            IClusterMessageBus clusterMessageBus,
                            String localNodeId,
                            IGroupManager groupManager) {
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
        this.localNodeId = localNodeId;
        this.groupManager = groupManager;
        this.pusher = IMExecutors.newVirtualThreadExecutor("im-pusher");
    }

    void deliver(Message msg) {
        String groupId = msg.getGroupId();

        if (groupId != null) {
            handleGroupDelivery(msg, groupId);
            return;
        }

        String toUserId = msg.getToUserId();
        if (toUserId == null) {
            log.warn("Delivery msg missing toUserId and groupId, seqId={}", msg.getSequenceId());
            return;
        }
        handleSingleDelivery(msg, toUserId);
    }

    void stop() {
        pusher.shutdown();
    }

    private void handleSingleDelivery(Message msg, String toUserId) {
        List<RouteBinding> bindings = routeTable != null
                ? routeTable.lookupAllBindings(toUserId)
                : List.of();

        if (bindings.isEmpty()) {
            log.info("User {} offline, skip push for msg {}", toUserId, msg.getSequenceId());
            return;
        }

        pushToBindings(msg, toUserId, bindings);
    }

    private void handleGroupDelivery(Message msg, String groupId) {
        Set<String> memberIds = groupManager != null
                ? groupManager.getMemberIds(groupId)
                : Set.of();

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

        for (String memberId : memberIds) {
            Message copy = msg.copyForUser(memberId);
            List<RouteBinding> bindings = routeTable != null
                    ? routeTable.lookupAllBindings(memberId)
                    : List.of();
            if (bindings.isEmpty()) {
                log.info("Group member {} offline, skip push for msg {}", memberId, msg.getSequenceId());
            }
            pushToBindings(copy, memberId, bindings);
        }

        log.info("Group {}: delivering msg {} to {} members", groupId, msg.getSequenceId(), memberIds.size());
    }

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
        if (clusterMessageBus == null) {
            throw new IllegalStateException("remote route " + nodeId
                    + " but no ClusterMessageBus, msg " + msg.getSequenceId() + " dropped");
        }
        ClusterMessage clusterMsg = ClusterMessage.fromMessage(localNodeId, msg, binding);
        boolean sent = clusterMessageBus.sendToNode(clusterMsg, nodeId);
        if (!sent) {
            throw new IllegalStateException("remote cluster delivery was not accepted by node " + nodeId);
        }
        log.info("Forwarded msg {} to remote node {} for user {} session {}",
                msg.getSequenceId(), nodeId, toUserId, binding.sessionId());
    }
}
