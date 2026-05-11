package com.im.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 集群节点信息。
 *
 * 每个 IM 实例就是一个 Node，通过 host:port 标识。
 * attrs 携带节点的附加属性（如负载、能力标记、所在区域）。
 */
public class NodeInformation {

    private final String nodeId;
    private final String host;
    private final int port;
    private final Map<String, String> attrs;

    public NodeInformation(String nodeId, String host, int port) {
        this(nodeId, host, port, Collections.emptyMap());
    }

    public NodeInformation(String nodeId, String host, int port, Map<String, String> attrs) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.attrs = attrs != null ? new HashMap<>(attrs) : Collections.emptyMap();
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

    public Map<String, String> getAttrs() {
        return attrs;
    }

    public String attr(String key) {
        return attrs.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeInformation nodeInformation)) return false;
        return port == nodeInformation.port && nodeId.equals(nodeInformation.nodeId) && host.equals(nodeInformation.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, host, port);
    }

    @Override
    public String toString() {
        return "NodeInformation{" + nodeId + ", " + host + ":" + port + "}";
    }
}
