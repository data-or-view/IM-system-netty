package com.im.api;

import java.util.Objects;

/**
 * A concrete online route for one user session.
 */
public record RouteBinding(String userId, String nodeId, int platformId, String sessionId, long expireAt) {

    public RouteBinding {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(nodeId, "nodeId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
    }

    public String routeField() {
        return platformId + ":" + sessionId;
    }

    public RouteNode toRouteNode(String localNodeId) {
        return nodeId.equals(localNodeId) ? RouteNode.local(nodeId) : RouteNode.remote(nodeId, null, 0);
    }
}
