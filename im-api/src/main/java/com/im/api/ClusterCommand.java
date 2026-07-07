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
    public static final int ANY_PLATFORM_ID = -1;
    public static final String DEFAULT_SESSION_ID = "default";

    public ClusterCommand {
        Preconditions.requireNonNull(type, "type");
        userId = Preconditions.requireText(userId, "userId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = DEFAULT_SESSION_ID;
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
        return new ClusterCommand(ClusterCommandType.KICK_USER, userId, ANY_PLATFORM_ID, DEFAULT_SESSION_ID, reason);
    }

    public static ClusterCommand kickPlatform(String userId, int platformId, String reason) {
        return new ClusterCommand(ClusterCommandType.KICK_PLATFORM, userId, platformId, DEFAULT_SESSION_ID, reason);
    }

    public static ClusterCommand kickSession(String userId, int platformId, String sessionId, String reason) {
        return new ClusterCommand(ClusterCommandType.KICK_SESSION, userId, platformId, sessionId, reason);
    }

    public static ClusterCommand pushEvent(String userId, Map<String, Object> payload) {
        return new ClusterCommand(
                ClusterCommandType.PUSH_EVENT, userId, ANY_PLATFORM_ID, DEFAULT_SESSION_ID, "PUSH_EVENT", payload);
    }
}
