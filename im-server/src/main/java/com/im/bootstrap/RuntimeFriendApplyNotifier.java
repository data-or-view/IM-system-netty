package com.im.bootstrap;

import com.im.api.ClusterMessage;
import com.im.api.FriendApply;
import com.im.api.FriendApplyNotifier;
import com.im.api.IClusterMessageBus;
import com.im.api.IRouteTable;
import com.im.core.friend.ClusterAwareFriendApplyNotifier;
import com.im.core.session.SessionManager;

final class RuntimeFriendApplyNotifier implements FriendApplyNotifier {
    private final String nodeId;
    private final SessionManager sessionManager;
    private volatile ClusterAwareFriendApplyNotifier delegate;

    RuntimeFriendApplyNotifier(String nodeId, SessionManager sessionManager) {
        this.nodeId = nodeId;
        this.sessionManager = sessionManager;
    }

    void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
        this.delegate = new ClusterAwareFriendApplyNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
    }

    @Override
    public void notifyApplyCreated(String toUserId, FriendApply apply) {
        if (delegate != null) delegate.notifyApplyCreated(toUserId, apply);
    }

    @Override
    public void notifyApplyHandled(String fromUserId, FriendApply apply) {
        if (delegate != null) delegate.notifyApplyHandled(fromUserId, apply);
    }

    void handleClusterPush(ClusterMessage message) {
        if (delegate != null) delegate.handleClusterPush(message);
    }
}
