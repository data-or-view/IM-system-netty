package com.im.api;

import java.util.Objects;

/**
 * Cluster-scoped control command.
 */
public record ClusterCommand(Type type, String userId, int platformId, String sessionId, String reason) {

    public enum Type {
        KICK_USER,
        KICK_PLATFORM,
        KICK_SESSION
    }

    public ClusterCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(userId, "userId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        if (reason == null || reason.isBlank()) {
            reason = type.name();
        }
    }

    public static ClusterCommand kickUser(String userId, String reason) {
        return new ClusterCommand(Type.KICK_USER, userId, -1, "default", reason);
    }

    public static ClusterCommand kickPlatform(String userId, int platformId, String reason) {
        return new ClusterCommand(Type.KICK_PLATFORM, userId, platformId, "default", reason);
    }

    public static ClusterCommand kickSession(String userId, int platformId, String sessionId, String reason) {
        return new ClusterCommand(Type.KICK_SESSION, userId, platformId, sessionId, reason);
    }
}
