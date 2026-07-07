package com.im.api;

import java.util.Objects;

/**
 * 集群节点间传输的消息包装。
 *
 * 需要携带来源节点信息（fromNodeId）、消息类型和 TTL 防止无限循环。
 */
public class ClusterMessage {
    private static final int DEFAULT_TTL = 3;

    private final ClusterMessageKind kind;
    private final String fromNodeId;
    private final Message message;
    private final ClusterCommand command;
    private final Integer targetPlatformId;
    private final String targetSessionId;
    private int ttl;

    public ClusterMessage(ClusterMessageKind kind, String fromNodeId, Message message) {
        this(kind, fromNodeId, message, null, null, null, DEFAULT_TTL);
    }

    public ClusterMessage(ClusterMessageKind kind, String fromNodeId, Message message, int ttl) {
        this(kind, fromNodeId, message, null, null, null, ttl);
    }

    public ClusterMessage(ClusterMessageKind kind, String fromNodeId, Message message,
                          Integer targetPlatformId, String targetSessionId, int ttl) {
        this(kind, fromNodeId, message, null, targetPlatformId, targetSessionId, ttl);
    }

    public ClusterMessage(ClusterMessageKind kind, String fromNodeId, ClusterCommand command) {
        this(kind, fromNodeId, null, command, null, null, DEFAULT_TTL);
    }

    public ClusterMessage(ClusterMessageKind kind, String fromNodeId, ClusterCommand command, int ttl) {
        this(kind, fromNodeId, null, command, null, null, ttl);
    }

    private ClusterMessage(ClusterMessageKind kind, String fromNodeId, Message message, ClusterCommand command,
                           Integer targetPlatformId, String targetSessionId, int ttl) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId");
        if (kind == ClusterMessageKind.USER_MESSAGE) {
            this.message = Objects.requireNonNull(message, "message");
            this.command = null;
            this.targetPlatformId = targetPlatformId;
            this.targetSessionId = normalizeSessionId(targetSessionId);
        } else {
            this.message = message;
            this.command = Objects.requireNonNull(command, "command");
            this.targetPlatformId = null;
            this.targetSessionId = null;
        }
        this.ttl = ttl;
    }

    /**
     * 从 Message 和来源节点创建 USER_MESSAGE 类型的 ClusterMessage。
     */
    public static ClusterMessage fromMessage(String fromNodeId, Message message) {
        return new ClusterMessage(ClusterMessageKind.USER_MESSAGE, fromNodeId, message);
    }

    public static ClusterMessage fromMessage(String fromNodeId, Message message, RouteBinding targetBinding) {
        Objects.requireNonNull(targetBinding, "targetBinding");
        return new ClusterMessage(ClusterMessageKind.USER_MESSAGE, fromNodeId, message,
                targetBinding.platformId(), targetBinding.sessionId(), DEFAULT_TTL);
    }

    public static ClusterMessage fromCommand(String fromNodeId, ClusterCommand command) {
        return new ClusterMessage(ClusterMessageKind.CLUSTER_COMMAND, fromNodeId, command);
    }

    public ClusterMessageKind getKind() {
        return kind;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public Message getMessage() {
        return message;
    }

    public ClusterCommand getCommand() {
        return command;
    }

    public Integer getTargetPlatformId() {
        return targetPlatformId;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public boolean hasTargetBinding() {
        return targetPlatformId != null && targetSessionId != null;
    }

    public int getTtl() {
        return ttl;
    }

    /** TTL 减一，返回 true 表示还有剩余跳数 */
    public boolean decrementTtl() {
        return --ttl > 0;
    }

    /**
     * 获取消息 topic。
     */
    public String getTopic() {
        if (kind == ClusterMessageKind.CLUSTER_COMMAND) {
            return ClusterMessageTopics.CLUSTER_COMMAND;
        }
        return message.getGroupId() != null ? ClusterMessageTopics.GROUP_CHAT : ClusterMessageTopics.SINGLE_CHAT;
    }

    @Override
    public String toString() {
        return "ClusterMessage{" + kind + ", from=" + fromNodeId + ", ttl=" + ttl + "}";
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessionId;
    }
}
