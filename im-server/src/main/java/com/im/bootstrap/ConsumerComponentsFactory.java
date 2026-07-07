package com.im.bootstrap;

import com.im.api.ClusterMessageTopics;
import com.im.config.Config;
import com.im.core.delivery.ClusterDeliveryHandler;
import com.im.core.delivery.ClusterSessionCommandHandler;
import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.reliability.BusinessMessageDlqCompensator;

final class ConsumerComponentsFactory {

    private ConsumerComponentsFactory() {
    }

    static ConsumerDependencies createConsumers(Config config,
                                                String nodeId,
                                                RuntimeDependencies runtime,
                                                ClusterDependencies cluster,
                                                StorageDependencies storage,
                                                BusinessDependencies business) {
        PersistenceConsumer persistenceConsumer = new PersistenceConsumer(
                storage.messageQueue(), storage.singleMessageStore(), storage.groupMessageStore(),
                business.conversationManager(), business.groupManager(),
                business.retryExecutor(), storage.sendMessageIdempotency(), storage.businessMessageDlqStore());
        DeliveryConsumer deliveryConsumer = new DeliveryConsumer(
                storage.messageQueue(), runtime.sessionManager(), cluster.routeTable(),
                cluster.clusterMessageBus(), nodeId, business.groupManager(),
                business.retryExecutor(), storage.sendMessageIdempotency(), storage.businessMessageDlqStore());
        BusinessMessageDlqCompensator businessMessageDlqCompensator = new BusinessMessageDlqCompensator(
                storage.messageQueue(),
                storage.businessMessageDlqStore(),
                config.getInt("im.mq.failure-compensation.batch-size", 100),
                config.getInt("im.mq.failure-compensation.max-attempts", 10),
                config.getLong("im.mq.failure-compensation.idle-interval-ms", 2000),
                config.getLong("im.mq.failure-compensation.base-delay-ms", 1000),
                config.getLong("im.mq.failure-compensation.claim-lease-ms", 30000));

        ClusterDeliveryHandler clusterDeliveryHandler = new ClusterDeliveryHandler(
                runtime.sessionManager(), cluster.routeTable(), nodeId);
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.SINGLE_CHAT, clusterDeliveryHandler);
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.GROUP_CHAT, clusterDeliveryHandler);
        cluster.clusterMessageBus().subscribe(
                ClusterMessageTopics.CLUSTER_COMMAND,
                new ClusterSessionCommandHandler(runtime.sessionManager()));
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.CLUSTER_COMMAND, runtime.friendApplyNotifier()::handleClusterPush);
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.CLUSTER_COMMAND, runtime.groupApplyNotifier()::handleClusterPush);
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.CLUSTER_COMMAND, runtime.systemMessageNotifier()::handleClusterPush);
        cluster.clusterMessageBus().subscribe(ClusterMessageTopics.CLUSTER_COMMAND, runtime.messageRevokeNotifier()::handleClusterPush);
        return new ConsumerDependencies(persistenceConsumer, deliveryConsumer, businessMessageDlqCompensator);
    }
}
