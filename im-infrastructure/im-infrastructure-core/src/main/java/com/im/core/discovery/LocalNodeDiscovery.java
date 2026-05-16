package com.im.core.discovery;

import com.im.api.INodeDiscovery;
import com.im.api.NodeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单机节点发现（开发/测试用）。
 *
 * 集群中只有一个节点（自身），所有路由都是本地路由。
 * register/unregister 无实际作用，保持自注册语义。
 *
 * 对应 OpenIM 的单机模式（conf.Standalone()）：
 *   OnlinePusher = DefaultAllNode(disCov, config)
 *   只返回自身一个节点
 */
public class LocalNodeDiscovery implements INodeDiscovery {

    private static final Logger log = LoggerFactory.getLogger(LocalNodeDiscovery.class);

    private volatile NodeInformation self;
    private final List<NodeEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
        log.info("LocalNodeDiscovery started (single-node mode)");
    }

    @Override
    public void stop() {
        running = false;
        if (self != null) {
            unregister();
        }
        listeners.clear();
        log.info("LocalNodeDiscovery stopped");
    }

    @Override
    public void register(NodeInformation self) {
        this.self = self;
        log.info("Node registered (local): {}", self);
        listeners.forEach(l -> l.onEvent(
                new INodeDiscovery.NodeEventListener.Event(
                        INodeDiscovery.NodeEventListener.EventType.NODE_ADDED, self)));
    }

    @Override
    public void unregister() {
        if (self != null) {
            NodeInformation leaving = this.self;
            this.self = null;
            listeners.forEach(l -> l.onEvent(
                    new INodeDiscovery.NodeEventListener.Event(
                            INodeDiscovery.NodeEventListener.EventType.NODE_REMOVED, leaving)));
            log.info("Node unregistered (local): {}", leaving);
        }
    }

    @Override
    public List<NodeInformation> aliveNodes() {
        if (self != null) {
            return List.of(self);
        }
        return List.of();
    }

    @Override
    public NodeInformation getNode(String nodeId) {
        if (self != null && self.getNodeId().equals(nodeId)) {
            return self;
        }
        return null;
    }

    @Override
    public void addListener(NodeEventListener listener) {
        listeners.add(listener);
    }
}
