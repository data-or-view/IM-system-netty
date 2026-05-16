package com.im.core.delivery;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息持久化消费者。
 *
 * 从 IMessageQueue 的 "persist" topic 消费消息：
 *   ① 写入 IMessageStore（完整消息存储）
 *   ② 更新 IConversationManager（会话最后一条消息 + 未读数）
 *
 * 与 ChatHandler 的 write-ahead save 构成双层持久化：
 *   ① ChatHandler.save()       ← 写前日志，防止消费者丢消息
 *   ② PersistenceConsumer.save()   ← 最终存储 + 会话更新
 */
public class PersistenceConsumer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConsumer.class);

    private final IMessageQueue messageQueue;
    private final IMessageStore messageStore;
    private final IConversationManager conversationManager;

    private volatile IMessageQueue.MessageHandler handler;

    public PersistenceConsumer(IMessageQueue messageQueue, IMessageStore messageStore) {
        this(messageQueue, messageStore, null);
    }

    public PersistenceConsumer(IMessageQueue messageQueue, IMessageStore messageStore,
                           IConversationManager conversationManager) {
        this.messageQueue = messageQueue;
        this.messageStore = messageStore;
        this.conversationManager = conversationManager;
    }

    @Override
    public void start() {
        this.handler = msg -> {
            String messageId = msg.getMessageId();
            String fromUserId = msg.getHeader("fromUserId");

            // ① 持久化消息（ChatHandler 已做 write-ahead 保存，此处为最终存储，允许重复）
            if (messageStore != null) {
                try {
                    messageStore.save(msg);
                } catch (Exception e) {
                    // 遍历异常链查找是否是重复键
                    boolean isDup = false;
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        String m = t.getMessage();
                        if (m != null && m.contains("Duplicate entry")) {
                            isDup = true;
                            break;
                        }
                    }
                    if (isDup) {
                        log.debug("Msg already saved (dup), seqId={}, mid={}", msg.getSeqId(), messageId);
                    } else {
                        log.warn("Persistence save failed: seqId={}, err={}", msg.getSeqId(), e.getMessage());
                    }
                }
            }

            // ② 更新会话
            if (conversationManager != null) {
                String groupId = msg.getHeader("groupId");
                String toUserId = msg.getHeader("toUserId");

                if (groupId != null) {
                    // 群聊：更新每个成员的会话
                    String conversationId = "group_" + groupId;
                    // 发送者的会话（不加未读数）
                    if (fromUserId != null) {
                        conversationManager.updateOnMessage(conversationId, fromUserId, msg, true);
                    }
                    // 注意：其他成员的会话在 DeliveryConsumer 展开后触发
                    // 目前由 DeliveryConsumer 的群聊展开逻辑负责，
                    // 它会为每个成员复制消息并 publish 到 DELIVER，
                    // 但不会再次走到 PERSIST。所以群成员会话在新消息到达时
                    // 由 DeliveryConsumer 独立处理（暂未实现）。
                    // 简化方案：群聊会话更新暂标记 TODO
                    log.debug("Group conversation update TBD for group {}", groupId);

                } else if (toUserId != null) {
                    // 单聊：两方的会话都要更新
                    String conversationId = buildConversationId(fromUserId, toUserId);

                    // 发送方：不加未读数
                    if (fromUserId != null) {
                        conversationManager.updateOnMessage(conversationId, fromUserId, msg, true);
                    }
                    // 接收方：+1 未读数
                    conversationManager.updateOnMessage(conversationId, toUserId, msg, false);
                }
            }

            log.debug("Persisted msg {} (conv updated)", messageId);
        };

        messageQueue.subscribe(MessageQueueTopics.PERSIST, handler);
        log.info("PersistenceConsumer subscribed to topic '{}'", MessageQueueTopics.PERSIST);
    }

    @Override
    public void stop() {
        if (handler != null) {
            messageQueue.unsubscribe(MessageQueueTopics.PERSIST, handler);
        }
        log.info("PersistenceConsumer stopped");
    }

    private String buildConversationId(String userA, String userB) {
        if (userA == null || userB == null) return null;
        String user1 = userA.compareTo(userB) <= 0 ? userA : userB;
        String user2 = userA.compareTo(userB) <= 0 ? userB : userA;
        return "single_" + user1 + "_" + user2;
    }
}
