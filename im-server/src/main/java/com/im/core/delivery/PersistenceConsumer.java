package com.im.core.delivery;

import com.im.api.*;
import com.im.common.retry.RetryExecutor;
import com.im.common.lifecycle.Lifecycle;
import com.im.core.reliability.ReliableMessageHandler;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.SendMessageIdempotency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final MessagePersistenceWorkflow workflow;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency idempotency;
    private final BusinessMessageDlqStore failureStore;

    private volatile QueueMessageHandler handler;

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
        this.workflow = new MessagePersistenceWorkflow(
                singleMessageStore, groupMessageStore, conversationManager, groupManager);
        this.retryExecutor = null;
        this.idempotency = null;
        this.failureStore = null;
    }

    public PersistenceConsumer(IMessageQueue messageQueue,
                               ISingleMessageStore singleMessageStore,
                               IGroupMessageStore groupMessageStore,
                               IConversationManager conversationManager,
                               IGroupManager groupManager,
                               RetryExecutor retryExecutor,
                               SendMessageIdempotency idempotency,
                               BusinessMessageDlqStore failureStore) {
        this.messageQueue = messageQueue;
        this.workflow = new MessagePersistenceWorkflow(
                singleMessageStore, groupMessageStore, conversationManager, groupManager);
        this.retryExecutor = retryExecutor;
        this.idempotency = idempotency;
        this.failureStore = failureStore;
    }

    @Override
    public void start() {
        QueueMessageHandler delegate = workflow::persist;

        this.handler = retryExecutor != null
                ? new ReliableMessageHandler(MessageQueueTopics.PERSIST, delegate,
                retryExecutor, idempotency, failureStore)
                : delegate;
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

}
