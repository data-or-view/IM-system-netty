package com.im.bootstrap;

import com.im.api.IClusterMessageBus;
import com.im.api.IMessageQueue;
import com.im.api.INodeDiscovery;
import com.im.api.NodeInformation;
import com.im.common.lifecycle.Lifecycle;
import com.im.core.call.CallStateManager;
import com.im.core.redis.RedisConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

record ServerRuntime(INodeDiscovery nodeDiscovery,
                     NodeInformation nodeInformation,
                     RequestAdmission requestAdmission,
                     Duration requestDrainTimeout,
                     IClusterMessageBus clusterMessageBus,
                     IMessageQueue messageQueue,
                     Lifecycle persistenceConsumer,
                     Lifecycle deliveryConsumer,
                     Lifecycle businessMessageDlqCompensator,
                     Lifecycle transportServer,
                     CallStateManager callStateManager,
                     Runnable connectionShutdown,
                     Runnable pendingAckShutdown,
                     Runnable sessionClear,
                     RedisConfiguration redisConfig,
                     ExecutorService virtualExecutor) implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(ServerRuntime.class);

    @Override
    public void start() throws Exception {
        // Shared cluster state and queues must be online before transport accepts
        // clients, otherwise early messages may be routed before this node is visible.
        nodeDiscovery.start();
        nodeDiscovery.register(nodeInformation);
        clusterMessageBus.start();
        messageQueue.start();
        persistenceConsumer.start();
        deliveryConsumer.start();
        businessMessageDlqCompensator.start();
        transportServer.start();
        requestAdmission.open();
    }

    @Override
    public void stop() {
        log.info("Shutting down...");
        requestAdmission.closeAndDrain(requestDrainTimeout);
        // Close network entry points first; the remaining shutdown order keeps routing
        // and persistence dependencies alive while in-flight work is being drained.
        transportServer.stop();
        businessMessageDlqCompensator.stop();
        deliveryConsumer.stop();
        persistenceConsumer.stop();
        messageQueue.stop();
        clusterMessageBus.stop();
        nodeDiscovery.unregister();
        nodeDiscovery.stop();
        connectionShutdown.run();
        if (callStateManager != null) callStateManager.shutdown();
        pendingAckShutdown.run();
        sessionClear.run();
        if (redisConfig != null) redisConfig.close();
        if (virtualExecutor != null) virtualExecutor.shutdown();
        log.info("Shutdown complete");
    }
}
