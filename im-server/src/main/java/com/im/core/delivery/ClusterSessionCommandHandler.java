package com.im.core.delivery;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageKind;
import com.im.api.ClusterMessageHandler;
import com.im.api.ISessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies cluster-wide session control commands on the local node.
 */
public class ClusterSessionCommandHandler implements ClusterMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ClusterSessionCommandHandler.class);

    private final ISessionManager sessionManager;

    public ClusterSessionCommandHandler(ISessionManager sessionManager) {
        this.sessionManager = sessionManager;
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
            case KICK_SESSION -> sessionManager.forceLogoutSession(
                    command.userId(), command.platformId(), command.sessionId());
        }
        log.info("Cluster session command applied: type={}, userId={}, platform={}, session={}, reason={}",
                command.type(), command.userId(), command.platformId(), command.sessionId(), command.reason());
    }
}
