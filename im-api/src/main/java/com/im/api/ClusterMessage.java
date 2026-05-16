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
    private int ttl;

    public ClusterMessage(Kind kind, String fromNodeId, Message message) {
        this(kind, fromNodeId, message, 3);
    }

    public ClusterMessage(Kind kind, String fromNodeId, Message message, int ttl) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId");
        this.message = Objects.requireNonNull(message, "message");
        this.ttl = ttl;
    }

    /**
     * 从 Message 和来源节点创建 USER_MESSAGE 类型的 ClusterMessage。
     */
    public static ClusterMessage fromMessage(String fromNodeId, Message message) {
        return new ClusterMessage(Kind.USER_MESSAGE, fromNodeId, message);
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
        return message.getGroupId() != null ? "GROUP_CHAT" : "SINGLE_CHAT";
    }

    @Override
    public String toString() {
        return "ClusterMessage{" + kind + ", from=" + fromNodeId + ", ttl=" + ttl + "}";
    }
}
