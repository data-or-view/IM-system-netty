package com.im.api;

/**
 * 集群节点间消息总线 topic。
 */
public final class ClusterMessageTopics {
    public static final String SINGLE_CHAT = "SINGLE_CHAT";
    public static final String GROUP_CHAT = "GROUP_CHAT";
    public static final String CLUSTER_COMMAND = "CLUSTER_COMMAND";

    private ClusterMessageTopics() {
    }
}
