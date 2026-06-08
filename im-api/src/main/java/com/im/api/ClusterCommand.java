package com.im.api;

import com.im.common.validation.Preconditions;

import java.util.Map;

/**
 * Cluster-scoped control command.
 */
public record ClusterCommand(ClusterCommandType type,
                             String userId,
                             int platformId,
                             String sessionId,
                             String reason,
                             Map<String, Object> payload) {

    public ClusterCommand {
        Preconditions.requireNonNull(type, "type");
        userId = Preconditions.requireText(userId, "userId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        if (reason == null || reason.isBlank()) {
            reason = type.name();
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public ClusterCommand(ClusterCommandType type, String userId, int platformId, String sessionId, String reason) {
        this(type, userId, platformId, sessionId, reason, Map.of());
    }

    public static ClusterCommand kickUser(String userId, String reason) {
        return new ClusterCommand(ClusterCommandType.KICK_USER, userId, -1, "default", reason);
    }

    public static ClusterCommand kickPlatform(String userId, int platformId, String reason) {
        return new ClusterCommand(ClusterCommandType.KICK_PLATFORM, userId, platformId, "default", reason);
    }

    public static ClusterCommand kickSession(String userId, int platformId, String sessionId, String reason) {
        return new ClusterCommand(ClusterCommandType.KICK_SESSION, userId, platformId, sessionId, reason);
    }

    public static ClusterCommand pushEvent(String userId, Map<String, Object> payload) {
        return new ClusterCommand(ClusterCommandType.PUSH_EVENT, userId, -1, "default", "PUSH_EVENT", payload);
    }
}
