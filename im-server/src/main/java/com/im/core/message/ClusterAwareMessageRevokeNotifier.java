package com.im.core.message;

import com.im.api.ClusterCommand;
import com.im.api.ClusterCommandType;
import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.ProtocolFields;
import com.im.api.PushEvent;
import com.im.api.RouteBinding;
import com.im.core.usecase.RevokeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClusterAwareMessageRevokeNotifier {

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareMessageRevokeNotifier.class);

    private final String localNodeId;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;

    public ClusterAwareMessageRevokeNotifier(String localNodeId,
                                             ISessionManager sessionManager,
                                             IRouteTable routeTable,
                                             IClusterMessageBus clusterMessageBus) {
        this.localNodeId = localNodeId;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
    }

    public void notify(RevokeResult result) {
        if (result == null || result.targetUserIds() == null || result.targetUserIds().isEmpty()) {
            return;
        }
        PushEvent event = new PushEvent(ProtocolFields.OP_MESSAGE_REVOKED, toData(result));
        for (String targetUserId : result.targetUserIds()) {
            push(targetUserId, event);
        }
    }

    public void handleClusterPush(ClusterMessage message) {
        if (message == null || message.getCommand() == null) {
            return;
        }
        ClusterCommand command = message.getCommand();
        if (command.type() != ClusterCommandType.PUSH_EVENT) {
            return;
        }
        Object op = command.payload().get(ProtocolFields.OP);
        Object data = command.payload().get(ProtocolFields.DATA);
        if (ProtocolFields.OP_MESSAGE_REVOKED.equals(op)) {
            RouteBinding binding = new RouteBinding(command.userId(), localNodeId,
                    command.platformId(), command.sessionId(), 0,
                    command.nodeIncarnation(), command.generation());
            pushLocal(binding,
                    new PushEvent(ProtocolFields.OP_MESSAGE_REVOKED, data));
        }
    }

    private void push(String userId, PushEvent event) {
        List<RouteBinding> bindings = routeTable != null ? routeTable.lookupAllBindings(userId) : List.of();
        if (bindings.isEmpty()) {
            pushAllLocal(userId, event);
            return;
        }

        long now = System.currentTimeMillis();
        for (RouteBinding binding : bindings) {
            if (binding.isExpired(now)) {
                routeTable.offline(binding);
                continue;
            }
            if (localNodeId.equals(binding.nodeId())) {
                pushLocal(binding, event);
            } else {
                forward(userId, binding, event);
            }
        }
    }

    private void pushAllLocal(String userId, PushEvent event) {
        for (IConnectionSession session : sessionManager.getSessionsByUserId(userId)) {
            if (session.getConnection().isActive()) {
                session.getConnection().write(event);
            }
        }
    }

    private void pushLocal(RouteBinding binding, PushEvent event) {
        int delivered = 0;
        for (IConnectionSession session : sessionManager.getSessionsByUserId(binding.userId())) {
            if (matches(session, binding.platformId(), binding.sessionId())
                    && session.getConnection().isActive()) {
                session.getConnection().write(event);
                delivered++;
            }
        }
        if (delivered == 0 && routeTable != null && routeTable.offlineIfCurrent(binding)) {
            log.warn("Removed stale revoke push route: userId={}, node={}, platform={}, session={}",
                    binding.userId(), binding.nodeId(), binding.platformId(), binding.sessionId());
        }
    }

    private boolean matches(IConnectionSession session, int platformId, String sessionId) {
        if (platformId < 0) {
            return true;
        }
        return session.getPlatformId() == platformId && session.getSessionId().equals(sessionId);
    }

    private void forward(String userId, RouteBinding binding, PushEvent event) {
        if (clusterMessageBus == null) {
            log.warn("Remote revoke push dropped: userId={}, targetNode={}, session={}",
                    userId, binding.nodeId(), binding.sessionId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ProtocolFields.OP, event.op());
        payload.put(ProtocolFields.DATA, event.data());
        ClusterCommand command = new ClusterCommand(
                ClusterCommandType.PUSH_EVENT,
                userId,
                binding.platformId(),
                binding.sessionId(),
                binding.nodeIncarnation(),
                binding.generation(),
                "PUSH_EVENT",
                payload);
        clusterMessageBus.sendToNode(ClusterMessage.fromCommand(localNodeId, command), binding.nodeId());
    }

    private static Map<String, Object> toData(RevokeResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ProtocolFields.CONVERSATION_ID, result.conversationId());
        data.put(ProtocolFields.SEQ, result.seq());
        data.put(ProtocolFields.REVOKER_ID, result.revokerId());
        return data;
    }
}
