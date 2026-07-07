package com.im.bootstrap;

import com.im.api.ClusterMessage;
import com.im.api.GroupApply;
import com.im.api.GroupApplyNotifier;
import com.im.api.IClusterMessageBus;
import com.im.api.IRouteTable;
import com.im.core.group.ClusterAwareGroupApplyNotifier;
import com.im.core.session.SessionManager;

import java.util.List;

final class RuntimeGroupApplyNotifier implements GroupApplyNotifier {
    private final String nodeId;
    private final SessionManager sessionManager;
    private volatile ClusterAwareGroupApplyNotifier delegate;

    RuntimeGroupApplyNotifier(String nodeId, SessionManager sessionManager) {
        this.nodeId = nodeId;
        this.sessionManager = sessionManager;
    }

    void bindCluster(IRouteTable routeTable, IClusterMessageBus clusterMessageBus) {
        this.delegate = new ClusterAwareGroupApplyNotifier(nodeId, sessionManager, routeTable, clusterMessageBus);
    }

    @Override
    public void notifyApplyCreated(List<String> managerUserIds, GroupApply apply) {
        if (delegate != null) delegate.notifyApplyCreated(managerUserIds, apply);
    }

    @Override
    public void notifyApplyHandled(String applicantUserId, GroupApply apply) {
        if (delegate != null) delegate.notifyApplyHandled(applicantUserId, apply);
    }

    void handleClusterPush(ClusterMessage message) {
        if (delegate != null) delegate.handleClusterPush(message);
    }
}
