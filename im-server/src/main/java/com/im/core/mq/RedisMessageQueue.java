package com.im.core.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.Message;
import com.im.api.IMessageQueue;
import com.im.core.redis.RedisConfiguration;
import com.im.core.redis.RedisConfiguration.CloseableRedisCommands;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import io.lettuce.core.Consumer;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Streams 消息队列。
 *
 * <p>使用 Redis Streams + Consumer Group 实现持久化消息队列：</p>
 * <ul>
 *   <li>每个 topic 对应一个 Redis Stream（key: {@code im:mq:stream:{topic}}）</li>
 *   <li>每个 topic 对应一个 Consumer Group（{@code im:mq:group:{topic}}）</li>
 *   <li>同一 group 内的消费者竞争消费（负载均衡）</li>
 *   <li>消息持久化在 Redis Stream 中，节点重启不丢失</li>
 * </ul>
 *
 * <p>行为：</p>
 * <ul>
 *   <li>{@link #publishAsync} → XADD 到流</li>
 *   <li>{@link #subscribe} → 启动虚拟线程做 XREADGROUP BLOCK</li>
 *   <li>{@link #unsubscribe} → 移除 handler，topic 无 handler 时停止消费者线程</li>
 * </ul>
 */
public class RedisMessageQueue implements IMessageQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageQueue.class);
    
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    /** Redis Stream key 前缀 */
    static final String STREAM_PREFIX = "im:mq:stream:";

    /** Consumer Group 前缀 */
    static final String GROUP_PREFIX = "im:mq:group:";

    /** XREADGROUP BLOCK 超时（必须小于 Redis command timeout 以避免 RedisCommandTimeoutException） */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    /** 每次 XREADGROUP 最大条数 */
    private static final int BATCH_SIZE = 10;

    /** 连接失败重试间隔 */
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    private final RedisConfiguration redisConfig;
    private final String consumerId;

    /** topic → handler 列表 */
    private final ConcurrentHashMap<String, List<MessageHandler>> subscribers = new ConcurrentHashMap<>();

    /** topic → 消费者任务 */
    private final ConcurrentHashMap<String, ConsumerTask> consumerTasks = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private RedisClusterAsyncCommands<String, String> async;

    public RedisMessageQueue(RedisConfiguration redisConfig, String nodeId) {
        this.redisConfig = redisConfig;
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        this.consumerId = nodeId + "_" + uuid;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        this.async = redisConfig.async();

        // 启动已有订阅的消费者线程
        for (String topic : subscribers.keySet()) {
            startConsumer(topic);
        }

        log.info("RedisMessageQueue started: consumerId={}", consumerId);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        List<ConsumerTask> tasks = new ArrayList<>(consumerTasks.values());
        consumerTasks.clear();
        for (ConsumerTask task : tasks) {
            task.stop();
        }

        log.info("RedisMessageQueue stopped: consumerId={}", consumerId);
    }

    @Override
    public void publishAsync(String topic, Message msg) {
        if (!running.get()) {
            log.warn("Queue not running, dropping message topic={}", topic);
            return;
        }

        String json;
        try {
            json = serialize(msg);
        } catch (Exception e) {
            log.error("Failed to serialize message for topic '{}': {}", topic, e.getMessage(), e);
            return;
        }

        String streamKey = streamKey(topic);

        // 先尝试异步发送（共享连接）
        try {
            async.xadd(streamKey, "payload", json);
            log.trace("Published to topic '{}': seqId={}", topic, msg.getSequenceId());
            return;
        } catch (Exception e) {
            log.warn("Async publish failed for topic '{}', falling back to sync: {}",
                    topic, e.getMessage());
        }

        // 异步发送失败时，使用专用同步连接重试（不共享 async 连接，避免竞争）
        try (CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
            RedisCommands<String, String> sync = redis.sync();
            sync.xadd(streamKey, "payload", json);
            log.info("Published to topic '{}' via sync fallback: seqId={}", topic, msg.getSequenceId());
        } catch (Exception e2) {
            log.error("Sync fallback also failed for topic '{}': {}", topic, e2.getMessage(), e2);
        }
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        if (running.get()) {
            startConsumer(topic);
        }
        log.info("Handler subscribed to topic '{}'", topic);
    }

    @Override
    public void unsubscribe(String topic, MessageHandler handler) {
        List<MessageHandler> handlers = subscribers.get(topic);
        if (handlers != null) {
            handlers.remove(handler);
            if (handlers.isEmpty()) {
                subscribers.remove(topic);
                ConsumerTask task = consumerTasks.remove(topic);
                if (task != null) {
                    task.stop();
                }
            }
        }
        log.info("Handler unsubscribed from topic '{}'", topic);
    }

    @Override
    public boolean hasSubscribers(String topic) {
        List<MessageHandler> handlers = subscribers.get(topic);
        return handlers != null && !handlers.isEmpty();
    }

    // ── Consumer Loop ──

    /**
     * 启动指定 topic 的消费者线程（虚拟线程）。
     * 每个 topic 只有一个消费者线程做 XREADGROUP BLOCK。
     */
    private void startConsumer(String topic) {
        consumerTasks.computeIfAbsent(topic, t -> {
            ConsumerTask task = new ConsumerTask(t);
            Thread thread = Thread.ofVirtual()
                    .name("redis-mq-" + t)
                    .start(task);
            task.thread = thread;
            return task;
        });
    }

    /**
     * 消费者任务 — 在独立连接中执行 XREADGROUP BLOCK 循环。
     */
    private class ConsumerTask implements Runnable {
        final String topic;
        volatile Thread thread;
        volatile boolean stopped = false;

        ConsumerTask(String topic) {
            this.topic = topic;
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            String streamKey = streamKey(topic);
            String groupName = groupName(topic);

            while (!stopped && running.get() && !Thread.currentThread().isInterrupted()) {
                try (RedisConfiguration.CloseableRedisCommands redis = redisConfig.createSyncCommands()) {
                    RedisCommands<String, String> sync = redis.sync();

                    // 确保 Consumer Group 存在（幂等）
                    ensureGroup(sync, streamKey, groupName);

                    while (!stopped && running.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            List<io.lettuce.core.StreamMessage<String, String>> messages = sync.xreadgroup(
                                    Consumer.from(groupName, consumerId),
                                    XReadArgs.Builder.block(BLOCK_TIMEOUT).count(BATCH_SIZE),
                                    XReadArgs.StreamOffset.lastConsumed(streamKey));

                            if (messages == null || messages.isEmpty()) continue;

                            for (io.lettuce.core.StreamMessage<String, String> msg : messages) {
                                if (stopped || !running.get()) break;

                                processMessage(sync, streamKey, groupName, msg);
                            }
                        } catch (Exception e) {
                            if (running.get() && !stopped) {
                                log.error("Consumer error on topic '{}': {}", topic, e.getMessage(), e);
                                sleepOrBreak();
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running.get() && !stopped) {
                        log.error("Connection error for topic '{}', retrying in {}ms: {}",
                                topic, RETRY_INTERVAL.toMillis(), e.getMessage());
                        sleepOrBreak();
                    }
                }
            }

            log.info("Consumer stopped for topic '{}'", topic);
        }

        private void ensureGroup(RedisCommands<String, String> sync, String streamKey, String groupName) {
            try {
                sync.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0-0"), groupName,
                        XGroupCreateArgs.Builder.mkstream(true));
            } catch (Exception ignored) {
                // Group already exists — 正常
            }
        }

        private void processMessage(RedisCommands<String, String> sync, String streamKey,
                                    String groupName, io.lettuce.core.StreamMessage<String, String> msg) {
            try {
                String payload = msg.getBody().get("payload");
                if (payload == null || payload.isEmpty()) {
                    log.warn("Message missing payload field, stream={}, id={}", streamKey, msg.getId());
                    sync.xack(streamKey, groupName, msg.getId());
                    return;
                }

                Message cmd = deserialize(payload);

                List<MessageHandler> handlers = subscribers.get(topic);
                if (handlers != null) {
                    for (MessageHandler handler : handlers) {
                        try {
                            handler.onMessage(cmd);
                        } catch (Exception e) {
                            log.error("Handler error on topic '{}': {}", topic, e.getMessage(), e);
                        }
                    }
                }

                sync.xack(streamKey, groupName, msg.getId());
            } catch (Exception e) {
                log.error("Failed to process message on topic '{}', id={}: {}",
                        topic, msg.getId(), e.getMessage(), e);
                try {
                    sync.xack(streamKey, groupName, msg.getId());
                } catch (Exception ignored) {
                }
            }
        }

        private void sleepOrBreak() {
            try {
                Thread.sleep(RETRY_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
        }
    }

    // ── Serialization ──

    /**
     * 序列化 Message 为 JSON 字符串。
     */
    private String serialize(Message msg) throws Exception {
        Map<String, Object> map = msg.toJsonMap();
        return MAPPER.writeValueAsString(map);
    }

    /**
     * 反序列化 JSON 字符串为 Message。
     */
    Message deserialize(String json) throws Exception {
        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        return Message.fromJsonMap(map);
    }

    // ── Key Helpers ──

    private static String streamKey(String topic) {
        return STREAM_PREFIX + topic;
    }

    private static String groupName(String topic) {
        return GROUP_PREFIX + topic;
    }
}
