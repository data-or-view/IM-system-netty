package com.im.api;

/**
 * 消息队列 Topic 常量。
 *
 * 参考 OpenIM 的消息 topic 分层：
 *   · ToPushTopic       → 在线推送（对应 DELIVER）
 *   · msgtransfer 消费并写入持久化存储（对应 PERSIST）
 */
public final class MessageQueueTopics {

    private MessageQueueTopics() {}

    /**
     * 消息投递：推送消息到目标用户。
     * DeliveryConsumer 消费此 topic，查路由 → 在线推 / 离线跳过。
     */
    public static final String DELIVER = "deliver";

    /**
     * 消息持久化：写入后端存储（DB / 消息历史）。
     * PersistenceConsumer 消费此 topic，写 IMessageStore。
     * 和 write-ahead save（ChatHandler 中的 save）互补：
     *   ① ChatHandler 先存（内存写前日志，防止 MQ 消费失败丢消息）
     *   ② PersistenceConsumer 消费后写入真实 DB（写后持久化）
     */
    public static final String PERSIST = "persist";
}
