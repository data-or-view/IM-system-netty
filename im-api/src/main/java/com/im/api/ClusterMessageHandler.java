package com.im.api;

/**
 * 集群消息处理器。
 */
@FunctionalInterface
public interface ClusterMessageHandler {

    /**
     * 处理收到的集群消息。
     *
     * @param msg 集群消息（含来源节点 + 原始 IMCommand + TTL）
     */
    void handle(ClusterMessage msg);
}
