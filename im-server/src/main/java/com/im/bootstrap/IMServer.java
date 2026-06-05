package com.im.bootstrap;

import com.im.api.NodeInformation;
import com.im.common.lifecycle.Lifecycle;
import com.im.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;

public class IMServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(IMServer.class);

    private final Config config;
    private final ServerRuntime runtime;

    static void resetDatabaseFailed() {
        ServerComponentsFactory.resetDatabaseFailed();
    }

    public IMServer(Config config) {
        this(config, ServerComponentsFactory.create(config));
    }

    IMServer(Config config, ServerComponents components) {
        this.config = config;
        this.runtime = components.runtime();
    }

    @Override
    public void start() throws Exception {
        // Cluster metadata and queues must be ready before transport opens, otherwise
        // a connected client can send messages before this node can route or persist them.
        runtime.nodeDiscovery().start();
        runtime.nodeDiscovery().register(buildNodeInformation());
        runtime.clusterMessageBus().start();
        runtime.messageQueue().start();
        runtime.persistenceConsumer().start();
        runtime.deliveryConsumer().start();
        runtime.transportServer().start();
    }

    @Override
    public void stop() {
        log.info("Shutting down...");
        // Stop accepting network traffic first, then drain/close internal consumers and
        // shared infrastructure so in-flight work is less likely to be abandoned mid-route.
        runtime.transportServer().stop();
        runtime.deliveryConsumer().stop();
        runtime.persistenceConsumer().stop();
        runtime.messageQueue().stop();
        runtime.clusterMessageBus().stop();
        runtime.nodeDiscovery().unregister();
        runtime.nodeDiscovery().stop();
        runtime.connectionEventHandler().shutdown();
        if (runtime.callStateManager() != null) runtime.callStateManager().shutdown();
        runtime.pendingAcknowledgementManager().shutdown();
        runtime.sessionManager().clear();
        if (runtime.redisConfig() != null) runtime.redisConfig().close();
        if (runtime.virtualExecutor() != null) runtime.virtualExecutor().shutdown();
        log.info("Shutdown complete");
    }

    private NodeInformation buildNodeInformation() {
        String host = "127.0.0.1";
        try { host = InetAddress.getLocalHost().getHostAddress(); } catch (Exception ignored) {}
        Map<String, String> attrs = new java.util.HashMap<>();
        int servicePort = config.getBoolean("im.ws.enabled", true) ? config.getInt("im.ws.port", 8081) : 0;
        attrs.put("webSocketPort", String.valueOf(servicePort));
        return new NodeInformation(
                config.getString("im.node.id", "node-1"), host, servicePort, attrs);
    }
}
