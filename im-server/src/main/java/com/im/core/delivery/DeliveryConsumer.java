package com.im.core.delivery;

import com.im.api.*;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.retry.RetryExecutor;
import com.im.core.reliability.ReliableMessageHandler;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.SendMessageIdempotency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消息投递消费者（并行推送）。
 *
 * 从 "deliver" topic 消费消息，并行推送到目标用户所在的所有在线节点。
 *
 * 单聊：
 *   routeTable.lookupAllBindings(toUserId) → 按 session 路由并行推送
 *
 * 群聊：
 *   groupManager.getMemberIds(groupId) → 展开每个成员 → 并行推送
 *
 * 设计参考 OpenIM 的 DefaultAllNode.GetConnsAndOnlinePush：
 *   通过 IRouteTable.lookupAllBindings 精确路由，替代 OpenIM 的广播模式。
 */
public class DeliveryConsumer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConsumer.class);

    private final IMessageQueue messageQueue;
    private final MessageDeliveryWorkflow workflow;
    private final RetryExecutor retryExecutor;
    private final SendMessageIdempotency idempotency;
    private final BusinessMessageDlqStore failureStore;

    private volatile QueueMessageHandler handler;

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId) {
        this(messageQueue, sessionManager, routeTable, clusterMessageBus, localNodeId, null);
    }

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId,
            IGroupManager groupManager) {
        this.messageQueue = messageQueue;
        this.workflow = new MessageDeliveryWorkflow(
                sessionManager, routeTable, clusterMessageBus, localNodeId, groupManager);
        this.retryExecutor = null;
        this.idempotency = null;
        this.failureStore = null;
    }

    public DeliveryConsumer(
            IMessageQueue messageQueue,
            ISessionManager sessionManager,
            IRouteTable routeTable,
            IClusterMessageBus clusterMessageBus,
            String localNodeId,
            IGroupManager groupManager,
            RetryExecutor retryExecutor,
            SendMessageIdempotency idempotency,
            BusinessMessageDlqStore failureStore) {
        this.messageQueue = messageQueue;
        this.workflow = new MessageDeliveryWorkflow(
                sessionManager, routeTable, clusterMessageBus, localNodeId, groupManager);
        this.retryExecutor = retryExecutor;
        this.idempotency = idempotency;
        this.failureStore = failureStore;
    }

    @Override
    public void start() {
        QueueMessageHandler delegate = workflow::deliver;

        this.handler = retryExecutor != null
                ? new ReliableMessageHandler(MessageQueueTopics.DELIVER, delegate,
                retryExecutor, idempotency, failureStore)
                : delegate;
        messageQueue.subscribe(MessageQueueTopics.DELIVER, handler);
        log.info("DeliveryConsumer subscribed to topic '{}'", MessageQueueTopics.DELIVER);
    }

    @Override
    public void stop() {
        if (handler != null) {
            messageQueue.unsubscribe(MessageQueueTopics.DELIVER, handler);
        }
        workflow.stop();
        log.info("DeliveryConsumer stopped");
    }
}
