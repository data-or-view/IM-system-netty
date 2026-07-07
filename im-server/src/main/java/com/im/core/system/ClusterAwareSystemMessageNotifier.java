package com.im.core.system;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.ProtocolFields;
import com.im.api.RouteBinding;
import com.im.api.SystemMessageNotifier;
import com.im.api.SystemMessageSummary;
import com.im.api.PushEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClusterAwareSystemMessageNotifier implements SystemMessageNotifier {

    public static final String OP_SYSTEM_MESSAGE = ProtocolFields.OP_SYSTEM_MESSAGE;

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareSystemMessageNotifier.class);

    private final String localNodeId;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;

    public ClusterAwareSystemMessageNotifier(String localNodeId,
                                             ISessionManager sessionManager,
                                             IRouteTable routeTable,
                                             IClusterMessageBus clusterMessageBus) {
        this.localNodeId = localNodeId;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
    }

    @Override
    public void notify(List<String> userIds, SystemMessageSummary summary) {
        PushEvent event = new PushEvent(OP_SYSTEM_MESSAGE, toData(summary));
        for (String userId : userIds != null ? userIds : List.<String>of()) {
            push(userId, event);
        }
    }

    public void handleClusterPush(ClusterMessage message) {
        if (message == null || message.getCommand() == null) {
            return;
        }
        ClusterCommand command = message.getCommand();
        Object op = command.payload().get(ProtocolFields.OP);
        Object data = command.payload().get(ProtocolFields.DATA);
        if (OP_SYSTEM_MESSAGE.equals(op)) {
            pushLocal(command.userId(), new PushEvent(OP_SYSTEM_MESSAGE, data));
        }
    }

    private void push(String userId, PushEvent event) {
        List<RouteBinding> bindings = routeTable != null ? routeTable.lookupAllBindings(userId) : List.of();
        for (RouteBinding binding : bindings) {
            if (binding.isExpired(System.currentTimeMillis())) {
                continue;
            }
            if (localNodeId.equals(binding.nodeId())) {
                pushLocal(userId, event);
            } else {
                forward(userId, event, binding.nodeId());
            }
        }
    }

    private void pushLocal(String userId, PushEvent event) {
        for (IConnectionSession session : sessionManager.getSessionsByUserId(userId)) {
            if (session.getConnection().isActive()) {
                session.getConnection().write(event);
            }
        }
    }

    private void forward(String userId, PushEvent event, String targetNodeId) {
        if (clusterMessageBus == null) {
            log.warn("Remote system message push dropped: userId={}, targetNode={}", userId, targetNodeId);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ProtocolFields.OP, event.op());
        payload.put(ProtocolFields.DATA, event.data());
        clusterMessageBus.sendToNode(ClusterMessage.fromCommand(localNodeId, ClusterCommand.pushEvent(userId, payload)), targetNodeId);
    }

    private static Map<String, Object> toData(SystemMessageSummary summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", summary.getMessageId());
        data.put("channelId", summary.getChannelId());
        data.put("channelName", summary.getChannelName());
        data.put("title", summary.getTitle());
        data.put("summary", summary.getSummary());
        data.put("priority", summary.getPriority());
        data.put("createdAt", summary.getCreatedAt());
        return data;
    }
}
