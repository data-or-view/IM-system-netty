package com.im.api;

import java.util.Objects;

/**
 * 集群节点间传输的消息包装。
 *
 * 为什么需要 ClusterMessage 而不是直接传 IMCommand：
 *   1. 需要携带来源节点信息（fromNodeId）
 *   2. 区分"用户消息转发"和"集群内部指令"
 *   3. 防止消息在集群中无限循环（ttl）
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
    private final IMCommand command;
    private int ttl;

    public ClusterMessage(Kind kind, String fromNodeId, IMCommand command) {
        this(kind, fromNodeId, command, 3);
    }

    public ClusterMessage(Kind kind, String fromNodeId, IMCommand command, int ttl) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId");
        this.command = Objects.requireNonNull(command, "command");
        this.ttl = ttl;
    }

    /**
     * 从 IMCommand 和来源节点创建 USER_MESSAGE 类型的 ClusterMessage。
     */
    public static ClusterMessage fromIMCommand(String fromNodeId, IMCommand command) {
        return new ClusterMessage(Kind.USER_MESSAGE, fromNodeId, command);
    }

    public Kind getKind() {
        return kind;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public IMCommand getCommand() {
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
     * 获取消息 topic（委托给 IMCommand 的 _op 头）。
     */
    public String getTopic() {
        return command.getType() != null ? command.getType().name() : "unknown";
    }

    @Override
    public String toString() {
        return "ClusterMessage{" + kind + ", from=" + fromNodeId + ", ttl=" + ttl + "}";
    }
}
