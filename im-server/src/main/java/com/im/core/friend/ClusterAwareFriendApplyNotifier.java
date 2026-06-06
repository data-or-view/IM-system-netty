package com.im.core.friend;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.FriendApply;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.ProtocolFields;
import com.im.api.RouteBinding;
import com.im.bootstrap.ws.WsPushEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClusterAwareFriendApplyNotifier implements FriendApplyNotifier {

    public static final String OP_FRIEND_APPLY = "friend.apply";

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareFriendApplyNotifier.class);

    private final String localNodeId;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final IClusterMessageBus clusterMessageBus;

    public ClusterAwareFriendApplyNotifier(String localNodeId,
                                           ISessionManager sessionManager,
                                           IRouteTable routeTable,
                                           IClusterMessageBus clusterMessageBus) {
        this.localNodeId = localNodeId;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.clusterMessageBus = clusterMessageBus;
    }

    @Override
    public void notifyApplyCreated(String toUserId, FriendApply apply) {
        push(toUserId, apply);
    }

    @Override
    public void notifyApplyHandled(String fromUserId, FriendApply apply) {
        push(fromUserId, apply);
    }

    public void handleClusterPush(ClusterMessage message) {
        if (message == null || message.getCommand() == null) {
            return;
        }
        ClusterCommand command = message.getCommand();
        Object op = command.payload().get(ProtocolFields.OP);
        Object data = command.payload().get(ProtocolFields.DATA);
        if (!(op instanceof String pushOp)) {
            return;
        }
        pushLocal(command.userId(), new WsPushEvent(pushOp, data));
    }

    private void push(String userId, FriendApply apply) {
        WsPushEvent event = new WsPushEvent(OP_FRIEND_APPLY, toData(apply));
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

    private void pushLocal(String userId, WsPushEvent event) {
        for (IConnectionSession session : sessionManager.getSessionsByUserId(userId)) {
            if (session.getConnection().isActive()) {
                session.getConnection().write(event);
            }
        }
    }

    private void forward(String userId, WsPushEvent event, String targetNodeId) {
        if (clusterMessageBus == null) {
            log.warn("Remote friend apply push dropped: userId={}, targetNode={}", userId, targetNodeId);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ProtocolFields.OP, event.op());
        payload.put(ProtocolFields.DATA, event.data());
        clusterMessageBus.sendToNode(ClusterMessage.fromCommand(localNodeId, ClusterCommand.pushEvent(userId, payload)), targetNodeId);
    }

    private static Map<String, Object> toData(FriendApply apply) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromUserId", apply.getFromUserId());
        data.put("toUserId", apply.getToUserId());
        data.put("reqMsg", apply.getReqMsg());
        data.put("handlerUserId", apply.getHandlerUserId());
        data.put("handleMsg", apply.getHandleMsg());
        data.put("handleResult", apply.getHandleResult().name());
        data.put("createTime", apply.getCreateTime());
        data.put("handleTime", apply.getHandleTime());
        return data;
    }
}
