package com.im.api;

import java.util.Objects;

/**
 * 路由结果：用户所在的节点地址。
 *
 * 如果用户与当前节点在同一节点，host = null（本地路由）。
 * 如果用户在不同节点，host:port 为远程节点地址。
 */
public class RouteNode {

    private final String nodeId;
    private final String host;
    private final int port;

    /** 本地路由（同节点） */
    public static RouteNode local(String nodeId) {
        return new RouteNode(nodeId, null, 0);
    }

    /** 远程路由（跨节点） */
    public static RouteNode remote(String nodeId, String host, int port) {
        return new RouteNode(nodeId, host, port);
    }

    public RouteNode(String nodeId, String host, int port) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = host;
        this.port = port;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isLocal() {
        return host == null;
    }

    public boolean isRemote() {
        return host != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RouteNode routeNode)) return false;
        return port == routeNode.port && nodeId.equals(routeNode.nodeId) && Objects.equals(host, routeNode.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, port);
    }

    @Override
    public String toString() {
        return isLocal() ? "local(" + nodeId + ")" : "remote(" + nodeId + "@" + host + ":" + port + ")";
    }
}
