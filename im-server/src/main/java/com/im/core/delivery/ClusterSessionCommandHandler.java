package com.im.core.delivery;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageKind;
import com.im.api.ClusterMessageHandler;
import com.im.api.ISessionManager;
import com.im.api.IRouteTable;
import com.im.api.RouteBinding;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies cluster-wide session control commands on the local node.
 */
public class ClusterSessionCommandHandler implements ClusterMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ClusterSessionCommandHandler.class);
    private static final int MAX_ROUTE_CLAIM_ATTEMPTS = 3;

    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final String localNodeId;
    private final String localNodeIncarnation;

    public ClusterSessionCommandHandler(ISessionManager sessionManager) {
        this(sessionManager, null, null, null);
    }

    public ClusterSessionCommandHandler(ISessionManager sessionManager, IRouteTable routeTable,
                                        String localNodeId, String localNodeIncarnation) {
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
        this.localNodeIncarnation = localNodeIncarnation;
    }

    @Override
    public void handle(ClusterMessage msg) {
        if (msg == null || msg.getKind() != ClusterMessageKind.CLUSTER_COMMAND || msg.getCommand() == null) {
            return;
        }

        ClusterCommand command = msg.getCommand();
        switch (command.type()) {
            case KICK_USER -> sessionManager.forceLogout(command.userId());
            case KICK_PLATFORM -> sessionManager.forceLogout(command.userId(), command.platformId());
            case KICK_SESSION -> {
                if (!claimCurrentBinding(command)) return;
                sessionManager.forceLogoutSession(command.userId(), command.platformId(), command.sessionId());
            }
            case PUSH_EVENT -> {
                return;
            }
        }
        log.info(StructuredLog.event(LogEvents.CLUSTER_SESSION_COMMAND_APPLIED,
                "commandType", command.type(),
                LogFields.USER_ID, command.userId(),
                LogFields.PLATFORM_ID, command.platformId(),
                LogFields.SESSION_ID, command.sessionId(),
                LogFields.SOURCE_NODE_ID, msg.getFromNodeId(),
                LogFields.REASON, command.reason()));
    }

    private boolean claimCurrentBinding(ClusterCommand command) {
        if (routeTable == null || localNodeId == null || localNodeIncarnation == null
                || !command.hasExactBindingIdentity()
                || !localNodeIncarnation.equals(command.nodeIncarnation())) {
            return false;
        }
        // A heartbeat deliberately rotates the mutable route generation to fence
        // stale delivery cleanup. Re-read the immutable session identity, then
        // CAS the current generation so a delayed SAME_TERM_KICK still applies.
        for (int attempt = 0; attempt < MAX_ROUTE_CLAIM_ATTEMPTS; attempt++) {
            boolean matchingBindingFound = false;
            for (RouteBinding current : routeTable.lookupAllBindings(command.userId())) {
                if (!localNodeId.equals(current.nodeId())
                        || !localNodeIncarnation.equals(current.nodeIncarnation())
                        || current.platformId() != command.platformId()
                        || !command.sessionId().equals(current.sessionId())) {
                    continue;
                }
                matchingBindingFound = true;
                if (routeTable.offlineIfCurrent(current)) {
                    return true;
                }
                break;
            }
            if (!matchingBindingFound) {
                return false;
            }
        }
        return false;
    }
}
