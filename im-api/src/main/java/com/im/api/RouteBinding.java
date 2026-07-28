package com.im.api;

import java.util.Objects;

/**
 * A concrete online route for one user session.
 */
public record RouteBinding(String userId, String nodeId, int platformId, String sessionId, long expireAt,
                           String nodeIncarnation, String generation) {

    public RouteBinding(String userId, String nodeId, int platformId, String sessionId, long expireAt) {
        this(userId, nodeId, platformId, sessionId, expireAt, null, null);
    }

    public RouteBinding(String userId, String nodeId, int platformId, String sessionId, long expireAt,
                        String generation) {
        this(userId, nodeId, platformId, sessionId, expireAt, null, generation);
    }

    public RouteBinding {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(nodeId, "nodeId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        if (generation != null && generation.isBlank()) {
            generation = null;
        }
        if (nodeIncarnation != null && nodeIncarnation.isBlank()) {
            nodeIncarnation = null;
        }
    }

    public String routeField() {
        return platformId + ":" + sessionId;
    }

    public boolean isExpired(long nowMillis) {
        return expireAt > 0 && expireAt <= nowMillis;
    }

    public boolean hasExactIdentity() {
        return nodeIncarnation != null && generation != null;
    }

    public boolean sameIdentity(RouteBinding other) {
        return other != null
                && userId.equals(other.userId)
                && nodeId.equals(other.nodeId)
                && platformId == other.platformId
                && sessionId.equals(other.sessionId)
                && Objects.equals(nodeIncarnation, other.nodeIncarnation)
                && Objects.equals(generation, other.generation);
    }

    public RouteNode toRouteNode(String localNodeId) {
        return nodeId.equals(localNodeId) ? RouteNode.local(nodeId) : RouteNode.remote(nodeId, nodeId, 0);
    }
}
