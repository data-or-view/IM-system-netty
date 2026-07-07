package com.im.core.group;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.GroupApply;
import com.im.api.GroupApplyNotifier;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.ProtocolFields;
import com.im.api.RouteBinding;
import com.im.api.PushEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClusterAwareGroupApplyNotifier implements GroupApplyNotifier {

    public static final String OP_GROUP_APPLY = ProtocolFields.OP_GROUP_APPLY;

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareGroupApplyNotifier.class);

    private final String localNodeId;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;

    public ClusterAwareGroupApplyNotifier(String localNodeId,
                                          ISessionManager sessionManager,
                                          IRouteTable routeTable,
                                          IClusterMessageBus clusterMessageBus) {
        this.localNodeId = localNodeId;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
    }

    @Override
    public void notifyApplyCreated(List<String> managerUserIds, GroupApply apply) {
        for (String managerUserId : managerUserIds) {
            push(managerUserId, apply);
        }
    }

    @Override
    public void notifyApplyHandled(String applicantUserId, GroupApply apply) {
        push(applicantUserId, apply);
    }

    public void handleClusterPush(ClusterMessage message) {
        if (message == null || message.getCommand() == null) {
            return;
        }
        ClusterCommand command = message.getCommand();
        Object op = command.payload().get(ProtocolFields.OP);
        Object data = command.payload().get(ProtocolFields.DATA);
        if (OP_GROUP_APPLY.equals(op)) {
            pushLocal(command.userId(), new PushEvent(OP_GROUP_APPLY, data));
        }
    }

    private void push(String userId, GroupApply apply) {
        PushEvent event = new PushEvent(OP_GROUP_APPLY, toData(apply));
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
            log.warn("Remote group apply push dropped: userId={}, targetNode={}", userId, targetNodeId);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ProtocolFields.OP, event.op());
        payload.put(ProtocolFields.DATA, event.data());
        clusterMessageBus.sendToNode(ClusterMessage.fromCommand(localNodeId, ClusterCommand.pushEvent(userId, payload)), targetNodeId);
    }

    private static Map<String, Object> toData(GroupApply apply) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupId", apply.getGroupId());
        data.put("userId", apply.getUserId());
        data.put("reqMsg", apply.getReqMsg());
        data.put("handledMsg", apply.getHandledMsg());
        data.put("handlerUserId", apply.getHandlerUserId());
        data.put("handleResult", apply.getHandleResult().name());
        data.put("joinSource", apply.getJoinSource().name());
        data.put("inviterUserId", apply.getInviterUserId());
        data.put("createTime", apply.getCreateTime());
        data.put("handledTime", apply.getHandledTime());
        return data;
    }
}
