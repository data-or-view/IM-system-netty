package com.im.core.delivery;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 消息持久化消费者。
 *
 * 从 IMessageQueue 的 "persist" topic 消费消息：
 *   ① 写入单聊/群聊消息存储端口（当前可委托到统一消息表）
 *   ② 更新 IConversationManager（会话最后一条消息 + 未读数）
 *
 * 与 ChatHandler 的 write-ahead save 构成双层持久化：
 *   ① ChatHandler.save()       ← 写前日志，防止消费者丢消息
 *   ② PersistenceConsumer.save()   ← 最终存储 + 会话更新
 */
public class PersistenceConsumer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConsumer.class);

    private final IMessageQueue messageQueue;
    private final ISingleMessageStore singleMessageStore;
    private final IGroupMessageStore groupMessageStore;
    private final IConversationManager conversationManager;
    private final IGroupManager groupManager;

    private volatile IMessageQueue.MessageHandler handler;

    public PersistenceConsumer(IMessageQueue messageQueue,
                               ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore) {
        this(messageQueue, singleMessageStore, groupMessageStore, null, null);
    }

    public PersistenceConsumer(IMessageQueue messageQueue,
                               ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore,
                               IConversationManager conversationManager) {
        this(messageQueue, singleMessageStore, groupMessageStore, conversationManager, null);
    }

    public PersistenceConsumer(IMessageQueue messageQueue,
                               ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore,
                               IConversationManager conversationManager,
                               IGroupManager groupManager) {
        this.messageQueue = messageQueue;
        this.singleMessageStore = singleMessageStore;
        this.groupMessageStore = groupMessageStore;
        this.conversationManager = conversationManager;
        this.groupManager = groupManager;
    }

    @Override
    public void start() {
        this.handler = msg -> {
            String messageId = msg.getMessageId();
            String fromUserId = msg.getFromUserId();

            // ① 持久化消息：业务链路面向单聊/群聊端口，物理存储仍可由统一表承接。
            tryPersist(msg, messageId);

            // ② 更新会话
            if (conversationManager != null) {
                String groupId = msg.getGroupId();
                String toUserId = msg.getToUserId();

                if (groupId != null) {
                    // 群聊：更新每个成员的会话
                    String conversationId = ConversationIds.group(groupId);

                    // 发送者：不加未读数
                    if (fromUserId != null) {
                        conversationManager.updateOnMessage(fromUserId, conversationId, msg, true);
                    }

                    // 其他成员：遍历群成员，更新会话 + 未读数
                    Set<String> memberIds = groupManager != null
                            ? groupManager.getMemberIds(groupId)
                            : Set.of();
                    for (String memberId : memberIds) {
                        if (!memberId.equals(fromUserId)) {
                            conversationManager.updateOnMessage(memberId, conversationId, msg, false);
                        }
                    }

                    log.debug("Group conv updated for {} members: groupId={}", memberIds.size(), groupId);

                } else if (toUserId != null) {
                    // 单聊：两方的会话都要更新
                    String conversationId = ConversationIds.single(fromUserId, toUserId);

                    // 发送方：不加未读数
                    if (fromUserId != null) {
                        conversationManager.updateOnMessage(fromUserId, conversationId, msg, true);
                    }
                    // 接收方：+1 未读数
                    conversationManager.updateOnMessage(toUserId, conversationId, msg, false);
                }
            }

            log.debug("Persisted msg {} (conv updated)", messageId);
        };

        messageQueue.subscribe(MessageQueueTopics.PERSIST, handler);
        log.info("PersistenceConsumer subscribed to topic '{}'", MessageQueueTopics.PERSIST);
    }

    private void tryPersist(Message msg, String messageId) {
        try {
            String groupId = msg.getGroupId();
            if (groupId != null && !groupId.isBlank()) {
                if (groupMessageStore != null) {
                    groupMessageStore.saveGroupMessage(msg);
                }
            } else if (singleMessageStore != null) {
                singleMessageStore.saveSingleMessage(msg);
            }
        } catch (Exception e) {
            if (isDuplicateEntry(e)) {
                log.debug("Msg already saved (dup), seqId={}, mid={}", msg.getMessageSeq(), messageId);
            } else {
                log.warn("Persistence save failed: seqId={}, err={}", msg.getMessageSeq(), e.getMessage());
            }
        }
    }

    private boolean isDuplicateEntry(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop() {
        if (handler != null) {
            messageQueue.unsubscribe(MessageQueueTopics.PERSIST, handler);
        }
        log.info("PersistenceConsumer stopped");
    }

}
