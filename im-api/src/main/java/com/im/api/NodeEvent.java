package com.im.api;

/**
 * 集群节点变更事件。
 */
public final class NodeEvent {
    private final NodeEventType type;
    private final NodeInformation node;

    public NodeEvent(NodeEventType type, NodeInformation node) {
        this.type = type;
        this.node = node;
    }

    public NodeEventType getType() {
        return type;
    }

    public NodeInformation getNode() {
        return node;
    }
}
