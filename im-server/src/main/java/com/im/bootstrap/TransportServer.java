package com.im.bootstrap;

import com.im.api.ISessionManager;
import com.im.common.lifecycle.Lifecycle;
import com.im.common.util.IMExecutors;
import com.im.config.Config;
import com.im.core.dispatcher.ApiDispatcher;
import com.im.core.handler.ConnectionEventHandler;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the network-facing server resources.
 *
 * <p>Transport resources have a different lifecycle from Redis, DB, and business
 * consumers: they are opened last so requests cannot enter before dependencies are
 * ready, and closed first so shutdown stops accepting new work before draining
 * infrastructure.</p>
 */
final class TransportServer implements Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransportServer.class);

    private final Config config;
    private final ISessionManager sessionManager;
    private final ConnectionEventHandler connectionEventHandler;
    private final ApiDispatcher dispatcher;
    private final ExecutorService virtualExecutor;
    private final RequestAdmission requestAdmission;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel wsChannel;
    private Channel httpChannel;
    private ScheduledExecutorService scanScheduler;

    TransportServer(Config config,
                    ISessionManager sessionManager,
                    ConnectionEventHandler connectionEventHandler,
                    ApiDispatcher dispatcher,
                    ExecutorService virtualExecutor) {
        this(config, sessionManager, connectionEventHandler, dispatcher, virtualExecutor, null);
    }

    TransportServer(Config config,
                    ISessionManager sessionManager,
                    ConnectionEventHandler connectionEventHandler,
                    ApiDispatcher dispatcher,
                    ExecutorService virtualExecutor,
                    RequestAdmission requestAdmission) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.connectionEventHandler = connectionEventHandler;
        this.dispatcher = dispatcher;
        this.virtualExecutor = virtualExecutor;
        this.requestAdmission = requestAdmission;
    }

    @Override
    public void start() throws Exception {
        boolean useEpoll = config.getBoolean("im.server.use-epoll", true) && Epoll.isAvailable();
        bossGroup = newBossGroup(useEpoll);
        workerGroup = newWorkerGroup(useEpoll);

        startIdleSessionScanner();
        startWebSocketIfEnabled(useEpoll);
        startHttpIfEnabled(useEpoll);

        log.info("Server transport started: nodeId={}, WS={}, HTTP={}",
                config.getString("im.node.id", "node-1"),
                config.getBoolean("im.ws.enabled", true) ? config.getInt("im.ws.port", 8081) : "disabled",
                config.getBoolean("im.http.enabled", true) ? config.getInt("im.http.port", 8082) : "disabled");
    }

    @Override
    public void stop() {
        if (scanScheduler != null) {
            scanScheduler.shutdown();
        }
        closeChannels();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup newBossGroup(boolean useEpoll) {
        int threads = config.getInt("im.server.boss-threads", 1);
        return useEpoll ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);
    }

    private EventLoopGroup newWorkerGroup(boolean useEpoll) {
        int threads = config.getInt("im.server.worker-threads", 0);
        return useEpoll ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);
    }

    private void startIdleSessionScanner() {
        scanScheduler = IMExecutors.newScheduledExecutor("im-scanner", 1);
        scanScheduler.scheduleAtFixedRate(
                () -> sessionManager.scanIdleSessions(config.getInt("im.server.heartbeat-timeout", 120)),
                30, 30, TimeUnit.SECONDS);
    }

    private void startWebSocketIfEnabled(boolean useEpoll) throws Exception {
        if (!config.getBoolean("im.ws.enabled", true)) {
            return;
        }
        wsChannel = WsServerBootstrap.start(bossGroup, workerGroup,
                config.getInt("im.ws.port", 8081), useEpoll,
                connectionEventHandler, dispatcher, virtualExecutor, requestAdmission);
    }

    private void startHttpIfEnabled(boolean useEpoll) throws Exception {
        if (!config.getBoolean("im.http.enabled", true)) {
            return;
        }
        httpChannel = HttpServerBootstrap.start(bossGroup, workerGroup,
                config.getInt("im.http.port", 8082), useEpoll,
                new com.im.bootstrap.http.HttpRequestAdapter(dispatcher, virtualExecutor, requestAdmission));
    }

    private void closeChannels() {
        try {
            if (wsChannel != null) wsChannel.close().sync();
            if (httpChannel != null) httpChannel.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing transport channels");
        }
    }
}
