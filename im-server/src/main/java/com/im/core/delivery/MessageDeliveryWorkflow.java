package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IGroupManager;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.common.util.IMExecutors;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.MessageObservability;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            Map<String, Object> fields = fields(msg);
            fields.put(LogFields.REASON, "missing_target");
            log.warn(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_FAILED, fields));
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
            Map<String, Object> fields = fields(msg);
            fields.put(LogFields.TO_USER_ID, toUserId);
            log.info(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_OFFLINE_SKIPPED, fields));
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
            Map<String, Object> fields = fields(msg);
            fields.put(LogFields.REASON, "no_group_targets");
            log.info(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_OFFLINE_SKIPPED, fields));
            return;
        }

        for (String memberId : memberIds) {
            Message copy = msg.copyForUser(memberId);
            List<RouteBinding> bindings = routeTable != null
                    ? routeTable.lookupAllBindings(memberId)
                    : List.of();
            if (bindings.isEmpty()) {
                Map<String, Object> fields = fields(copy);
                fields.put(LogFields.TO_USER_ID, memberId);
                log.debug(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_OFFLINE_SKIPPED, fields));
            }
            pushToBindings(copy, memberId, bindings);
        }

        Map<String, Object> fields = fields(msg);
        fields.put(LogFields.TARGET_COUNT, memberIds.size());
        log.info(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_ROUTE_RESOLVED, fields));
    }

    private void pushToBindings(Message msg, String toUserId, List<RouteBinding> bindings) {
        List<Future<?>> futures = new ArrayList<>();
        long now = System.currentTimeMillis();
        Map<String, Object> routeFields = fields(msg);
        routeFields.put(LogFields.TO_USER_ID, toUserId);
        routeFields.put(LogFields.ROUTE_COUNT, bindings.size());
        log.info(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_ROUTE_RESOLVED, routeFields));
        for (RouteBinding binding : bindings) {
            if (binding.isExpired(now)) {
                Map<String, Object> fields = fields(msg);
                fields.put(LogFields.TO_USER_ID, toUserId);
                fields.put(LogFields.PLATFORM_ID, binding.platformId());
                fields.put(LogFields.SESSION_ID, binding.sessionId());
                fields.put(LogFields.NODE_ID, localNodeId);
                fields.put(LogFields.TARGET_NODE_ID, binding.nodeId());
                fields.put(LogFields.REASON, "expired_route");
                log.debug(StructuredLog.event(LogEvents.CLUSTER_STALE_ROUTE_REMOVED, fields));
                routeTable.offline(binding);
                continue;
            }
            RouteNode route = binding.toRouteNode(localNodeId);
            if (route.isLocal()) {
                futures.add(pusher.submit(() -> runWithMessageMdc(msg,
                        () -> pushToLocalBinding(msg, toUserId, binding))));
            } else {
                futures.add(pusher.submit(() -> runWithMessageMdc(msg,
                        () -> forwardToRemoteNode(msg, toUserId, route.getNodeId(), binding))));
            }
        }
        waitForPushes(msg, futures);
    }

    private void waitForPushes(Message msg, List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("delivery interrupted", e);
            } catch (Exception e) {
                Map<String, Object> fields = fields(msg);
                fields.put(LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName());
                fields.put(LogFields.REASON, "delivery_push_failed");
                log.warn(StructuredLog.event(LogEvents.MESSAGE_DELIVERY_FAILED, fields));
                throw new IllegalStateException("delivery push failed", e);
            }
        }
    }

    private void runWithMessageMdc(Message msg, Runnable task) {
        try (MessageObservability.Scope ignored = MessageObservability.bind(MessageQueueTopics.DELIVER, msg)) {
            task.run();
        }
    }

    private void pushToLocalBinding(Message msg, String toUserId, RouteBinding binding) {
        List<IConnectionSession> sessions = sessionManager.getSessionsByUserId(toUserId);
        for (IConnectionSession session : sessions) {
            if (matches(binding, session) && session.getConnection().isActive()) {
                session.getConnection().write(msg);
                Map<String, Object> fields = fields(msg);
                fields.put(LogFields.TO_USER_ID, toUserId);
                fields.put(LogFields.SESSION_ID, session.getSessionId());
                fields.put(LogFields.PLATFORM_ID, session.getPlatformId());
                fields.put(LogFields.NODE_ID, localNodeId);
                log.info(StructuredLog.event(LogEvents.MESSAGE_PUSH_LOCAL_SUCCEEDED, fields));
                return;
            }
        }
        Map<String, Object> fields = fields(msg);
        fields.put(LogFields.TO_USER_ID, toUserId);
        fields.put(LogFields.PLATFORM_ID, binding.platformId());
        fields.put(LogFields.SESSION_ID, binding.sessionId());
        fields.put(LogFields.NODE_ID, localNodeId);
        fields.put(LogFields.REASON, "local_session_missing");
        log.warn(StructuredLog.event(LogEvents.CLUSTER_STALE_ROUTE_REMOVED, fields));
        routeTable.offline(binding);
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
            Map<String, Object> fields = fields(msg);
            fields.put(LogFields.TO_USER_ID, toUserId);
            fields.put(LogFields.SOURCE_NODE_ID, localNodeId);
            fields.put(LogFields.TARGET_NODE_ID, nodeId);
            fields.put(LogFields.SESSION_ID, binding.sessionId());
            log.warn(StructuredLog.event(LogEvents.MESSAGE_FORWARD_REMOTE_FAILED, fields));
            throw new IllegalStateException("remote cluster delivery was not accepted by node " + nodeId);
        }
        Map<String, Object> fields = fields(msg);
        fields.put(LogFields.TO_USER_ID, toUserId);
        fields.put(LogFields.SOURCE_NODE_ID, localNodeId);
        fields.put(LogFields.TARGET_NODE_ID, nodeId);
        fields.put(LogFields.SESSION_ID, binding.sessionId());
        fields.put(LogFields.PLATFORM_ID, binding.platformId());
        log.info(StructuredLog.event(LogEvents.MESSAGE_FORWARD_REMOTE_SUCCEEDED, fields));
    }

    private Map<String, Object> fields(Message msg) {
        return new LinkedHashMap<>(MessageObservability.fields(MessageQueueTopics.DELIVER, msg));
    }
}
