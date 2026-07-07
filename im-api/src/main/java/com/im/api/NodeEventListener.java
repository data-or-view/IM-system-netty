package com.im.api;

/**
 * 节点变更监听器。
 */
@FunctionalInterface
public interface NodeEventListener {
    void onEvent(NodeEvent event);
}
