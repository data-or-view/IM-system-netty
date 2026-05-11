package com.im.core.mq;

import com.im.api.ILifecycle;
import com.im.api.IMCommand;
import com.im.api.IMessageQueue;
import com.im.core.util.IMExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * 内存消息队列（单机开发/测试用）。
 *
 * 行为：
 *   · publish → 所有订阅该 topic 的 handler 异步执行（虚拟线程）
 *   · 无序，非持久化，节点重启消息丢失
 *   · 每个 handler 在自己的虚拟线程中运行，互不阻塞
 *
 * 生产环境请替换为 KafkaQueue / RocketMQQueue。
 */
public class MemoryMessageQueue implements IMessageQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryMessageQueue.class);

    /** topic → handler 列表 */
    private final ConcurrentHashMap<String, List<MessageHandler>> subscribers = new ConcurrentHashMap<>();

    /** 分发执行器（虚拟线程，每个 handler 独立执行） */
    private final ExecutorService dispatcher;

    private volatile boolean running = false;

    public MemoryMessageQueue() {
        this.dispatcher = IMExecutors.newVirtualThreadExecutor("im-mq");
    }

    @Override
    public void start() {
        running = true;
        log.info("MemoryMessageQueue started");
    }

    @Override
    public void shutdown() {
        running = false;
        dispatcher.shutdown();
        subscribers.clear();
        log.info("MemoryMessageQueue stopped");
    }

    @Override
    public void publishAsync(String topic, IMCommand msg) {
        if (!running) {
            log.warn("Queue not running, dropping message topic={}", topic);
            return;
        }

        List<MessageHandler> handlers = subscribers.get(topic);
        if (handlers == null || handlers.isEmpty()) {
            log.debug("No subscribers for topic '{}', message dropped", topic);
            return;
        }

        for (MessageHandler handler : handlers) {
            dispatcher.execute(() -> {
                try {
                    handler.onMessage(msg);
                } catch (Exception e) {
                    log.error("Handler error on topic '{}': {}", topic, e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("Handler subscribed to topic '{}'", topic);
    }

    @Override
    public void unsubscribe(String topic, MessageHandler handler) {
        List<MessageHandler> handlers = subscribers.get(topic);
        if (handlers != null) {
            handlers.remove(handler);
            log.info("Handler unsubscribed from topic '{}'", topic);
        }
    }

    @Override
    public boolean hasSubscribers(String topic) {
        List<MessageHandler> handlers = subscribers.get(topic);
        return handlers != null && !handlers.isEmpty();
    }

    @Override
    public Set<String> topics() {
        return subscribers.keySet();
    }
}
