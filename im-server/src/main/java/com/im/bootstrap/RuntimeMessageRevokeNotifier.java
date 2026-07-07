package com.im.bootstrap;

import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IRouteTable;
import com.im.core.message.ClusterAwareMessageRevokeNotifier;
import com.im.core.session.SessionManager;
import com.im.core.usecase.RevokeResult;

final class RuntimeMessageRevokeNotifier {
    private final String nodeId;
    private final SessionManager sessionManager;
    private volatile ClusterAwareMessageRevokeNotifier delegate;

    RuntimeMessageRevokeNotifier(String nodeId, SessionManager sessionManager) {
        this.nodeId = nodeId;
        this.sessionManager = sessionManager;
    }

    void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
        this.delegate = new ClusterAwareMessageRevokeNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
    }

    void notify(RevokeResult result) {
        if (delegate != null) delegate.notify(result);
    }

    void handleClusterPush(ClusterMessage message) {
        if (delegate != null) delegate.handleClusterPush(message);
    }
}
