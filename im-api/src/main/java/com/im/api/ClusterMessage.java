package com.im.api;

import java.util.Objects;

/**
 * 集群节点间传输的消息包装。
 *
 * 需要携带来源节点信息（fromNodeId）、消息类型（Kind）和 TTL 防止无限循环。
 */
public class ClusterMessage {

    /** 消息类型 */
    public enum Kind {
        /** 用户消息转发 */
        USER_MESSAGE,
        /** 集群内部指令 */
        CLUSTER_COMMAND,
    }

    private final Kind kind;
    private final String fromNodeId;
    private final Message message;
    private final ClusterCommand command;
    private int ttl;

    public ClusterMessage(Kind kind, String fromNodeId, Message message) {
        this(kind, fromNodeId, message, null, 3);
    }

    public ClusterMessage(Kind kind, String fromNodeId, Message message, int ttl) {
        this(kind, fromNodeId, message, null, ttl);
    }

    public ClusterMessage(Kind kind, String fromNodeId, ClusterCommand command) {
        this(kind, fromNodeId, null, command, 3);
    }

    public ClusterMessage(Kind kind, String fromNodeId, ClusterCommand command, int ttl) {
        this(kind, fromNodeId, null, command, ttl);
    }

    private ClusterMessage(Kind kind, String fromNodeId, Message message, ClusterCommand command, int ttl) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId");
        if (kind == Kind.USER_MESSAGE) {
            this.message = Objects.requireNonNull(message, "message");
            this.command = null;
        } else {
            this.message = message;
            this.command = Objects.requireNonNull(command, "command");
        }
        this.ttl = ttl;
    }

    /**
     * 从 Message 和来源节点创建 USER_MESSAGE 类型的 ClusterMessage。
     */
    public static ClusterMessage fromMessage(String fromNodeId, Message message) {
        return new ClusterMessage(Kind.USER_MESSAGE, fromNodeId, message);
    }

    public static ClusterMessage fromCommand(String fromNodeId, ClusterCommand command) {
        return new ClusterMessage(Kind.CLUSTER_COMMAND, fromNodeId, command);
    }

    public Kind getKind() {
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
        if (kind == Kind.CLUSTER_COMMAND) {
            return "CLUSTER_COMMAND";
        }
        return message.getGroupId() != null ? "GROUP_CHAT" : "SINGLE_CHAT";
    }

    @Override
    public String toString() {
        return "ClusterMessage{" + kind + ", from=" + fromNodeId + ", ttl=" + ttl + "}";
    }
}
