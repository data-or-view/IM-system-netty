package com.im.api;

/**
 * 消息队列订阅处理器。
 */
@FunctionalInterface
public interface QueueMessageHandler {
    void onMessage(Message msg);
}
