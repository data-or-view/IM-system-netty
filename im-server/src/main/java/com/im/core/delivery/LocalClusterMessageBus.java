package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageHandler;
import com.im.api.IClusterMessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单机集群消息总线（开发/测试用）。
 *
 * 单节点模式无需跨节点转发，所有 sendToNode / broadcast 都是 no-op。
 * 生产环境替换为 NettyP2PBus / KafkaBus。
 */
public class LocalClusterMessageBus implements IClusterMessageBus {

    private static final Logger log = LoggerFactory.getLogger(LocalClusterMessageBus.class);

    @Override
    public void start() {
        log.info("LocalClusterMessageBus started (single-node, no-remote-forward)");
    }

    @Override
    public void stop() {
        log.info("LocalClusterMessageBus stopped");
    }

    @Override
    public void sendToNode(ClusterMessage msg, String targetNodeId) {
        log.debug("LocalClusterMessageBus: sendToNode skipped (single-node), target={}, topic={}",
                targetNodeId, msg.getTopic());
    }

    @Override
    public void broadcast(ClusterMessage msg) {
        log.debug("LocalClusterMessageBus: broadcast skipped (single-node), topic={}", msg.getTopic());
    }

    @Override
    public void subscribe(String topic, ClusterMessageHandler handler) {
        // 单机模式无需订阅
    }

    @Override
    public void unsubscribe(String topic, ClusterMessageHandler handler) {
        // 单机模式无需取消
    }
}
