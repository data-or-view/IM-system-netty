package com.im.bootstrap;

import com.im.api.IClusterMessageBus;
import com.im.api.IMessageQueue;
import com.im.api.INodeDiscovery;
import com.im.core.call.CallStateManager;
import com.im.core.delivery.DeliveryConsumer;
import com.im.core.delivery.PersistenceConsumer;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.core.handler.ConnectionEventHandler;
import com.im.core.redis.RedisConfiguration;
import com.im.core.session.SessionManager;

import java.util.concurrent.ExecutorService;

record ServerRuntime(INodeDiscovery nodeDiscovery,
                     IClusterMessageBus clusterMessageBus,
                     IMessageQueue messageQueue,
                     PersistenceConsumer persistenceConsumer,
                     DeliveryConsumer deliveryConsumer,
                     TransportServer transportServer,
                     ConnectionEventHandler connectionEventHandler,
                     CallStateManager callStateManager,
                     PendingAcknowledgementManager pendingAcknowledgementManager,
                     SessionManager sessionManager,
                     RedisConfiguration redisConfig,
                     ExecutorService virtualExecutor) {
}
