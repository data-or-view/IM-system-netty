package com.im.bootstrap;

import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IRouteTable;
import com.im.api.SystemMessageNotifier;
import com.im.api.SystemMessageSummary;
import com.im.core.session.SessionManager;
import com.im.core.system.ClusterAwareSystemMessageNotifier;

import java.util.List;

final class RuntimeSystemMessageNotifier implements SystemMessageNotifier {
    private final String nodeId;
    private final SessionManager sessionManager;
    private volatile ClusterAwareSystemMessageNotifier delegate;

    RuntimeSystemMessageNotifier(String nodeId, SessionManager sessionManager) {
        this.nodeId = nodeId;
        this.sessionManager = sessionManager;
    }

    void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
        this.delegate = new ClusterAwareSystemMessageNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
    }

    @Override
    public void notify(List<String> userIds, SystemMessageSummary summary) {
        if (delegate != null) delegate.notify(userIds, summary);
    }

    void handleClusterPush(ClusterMessage message) {
        if (delegate != null) delegate.handleClusterPush(message);
    }
}
