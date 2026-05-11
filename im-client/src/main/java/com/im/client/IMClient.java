package com.im.client;

import com.im.api.CommandType;
import com.im.api.ILifecycle;
import com.im.api.IMCommand;
import com.im.client.handler.ClientConnectHandler;
import com.im.client.handler.ClientMessageHandler;
import com.im.codec.IMDecoder;
import com.im.codec.IMEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.im.core.PendingAcknowledgementManager;
import com.im.core.util.IMExecutors;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * IM 客户端主类，参考 RocketMQ 的 NettyRemotingClient + MQClientInstance。
 *
 * 负责：
 *   ① 启动 Netty Bootstrap，连接 IM 服务端
 *   ② 连接就绪后自动发送 LOGIN 认证
 *   ③ 定时发送 HEARTBEAT（类似 RocketMQ 的 sendHeartbeatToAllBrokerWithLock）
 *   ④ 断线自动重连（通过 ChannelWrapper，类似 RocketMQ 的 getAndCreateChannelAsync）
 *   ⑤ ACK 配对（通过 PendingAcknowledgementManager）
 *
 * 核心流程：
 *   1. start() → 创建 Bootstrap + EventLoopGroup + 初始化 Pipeline
 *   2. connect() → 异步连接，创建 ChannelWrapper
 *   3. onChannelActive → 发送 LOGIN（含 userId）
 *   4. onLoginResult(true) → 启动 heartbeat 定时器
 *   5. heartbeat 发送 → 每 30s 发 HEARTBEAT
 *   6. channelInactive → 从 channelTables 移除
 *   7. getChannel() → 发现 isOK=false → 自动重建连接（懒重连）
 */
public class IMClient implements ILifecycle {

    private static final Logger log = LoggerFactory.getLogger(IMClient.class);

    private final ClientConfiguration config;
    private final ClientMessageHandler messageHandler;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;

    private EventLoopGroup eventLoopGroup;
    private Bootstrap bootstrap;
    private volatile ChannelWrapper channelWrapper;
    private volatile boolean loggedIn = false;
    private volatile boolean running = false;

    /** 登录结果回调 */
    private volatile Consumer<Boolean> loginCallback;

    /** 心跳定时器 */
    private ScheduledExecutorService heartbeatScheduler;

    /** 登录超时定时器 */
    private ScheduledFuture<?> loginTimeoutFuture;

    public IMClient(ClientConfiguration config) {
        this.config = config;
        this.pendingAcknowledgementManager = new PendingAcknowledgementManager();
        this.messageHandler = new ClientMessageHandler(this);
    }

    // ==================== Lifecycle ====================

    @Override
    public void start() {
        this.running = true;

        // 1. EventLoopGroup
        eventLoopGroup = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "im-client-selector");
            t.setDaemon(true);
            return t;
        });

        // 2. Bootstrap
        bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new IMDecoder());
                        p.addLast(new IMEncoder());
                        // 120s 无 IO 认为空闲 → 触发重连
                        p.addLast(new IdleStateHandler(0, 0, config.getHeartbeatTimeoutSeconds()));
                        p.addLast(new ClientConnectHandler(IMClient.this));
                        p.addLast(messageHandler);
                    }
                });

        // 3. 发起连接
        connect();

        log.info("IMClient started, target={}", config.getServerAddress());
    }

    @Override
    public void shutdown() {
        this.running = false;

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        if (loginTimeoutFuture != null) {
            loginTimeoutFuture.cancel(false);
        }

        if (channelWrapper != null) {
            channelWrapper.close();
        }
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }

        pendingAcknowledgementManager.shutdown();
        log.info("IMClient shutdown");
    }

    // ==================== Connection ====================

    /**
     * 连接（或重新连接）到服务端。
     * 参考 RocketMQ 的 createChannelAsync 模式。
     */
    public synchronized void connect() {
        if (!running) return;

        // 已经有可用连接就不重建
        if (channelWrapper != null && channelWrapper.isOK()) {
            return;
        }

        ChannelFuture future = doConnect();
        channelWrapper = new ChannelWrapper(this, config.getServerAddress(), future);
        log.info("Connecting to {} async...", config.getServerAddress());
    }

    /**
     * 执行实际连接，返回 ChannelFuture。
     * 由 ChannelWrapper.reconnect() 调用。
     */
    ChannelFuture doConnect() {
        return bootstrap.connect(config.getServerHost(), config.getServerPort());
    }

    /**
     * 获取当前可用的 Channel。
     * 如果当前连接不可用，触发懒重连（通过 connect()）。
     *
     * 参考 RocketMQ 的 getAndCreateChannel() + ChannelWrapper.isOK()。
     */
    public Channel getChannel() {
        if (channelWrapper == null || !channelWrapper.isOK()) {
            connect(); // lazy reconnect
            return null;
        }
        return channelWrapper.getChannel();
    }

    /**
     * 获取 ChannelWrapper（直接暴露，不给重连触发）。
     */
    public ChannelWrapper getChannelWrapper() {
        return channelWrapper;
    }

    // ==================== Event: Channel Active ====================

    /**
     * Channel 激活时触发（由 ClientConnectHandler.channelActive 调用）。
     * 1. 将当前 ChannelWrapper 与新 channel 关联
     * 2. 发送 LOGIN 认证
     * 3. 启动登录超时定时器
     */
    public void onChannelActive(Channel channel) {
        // 设置登录中状态
        this.loggedIn = false;

        // 发送 LOGIN
        IMCommand login = new IMCommand(CommandType.LOGIN);
        login.putHeader("userId", config.getUserId());
        login.putHeader("clientVersion", "1.0.0");
        channel.writeAndFlush(login);
        log.info("Login sent for userId={}", config.getUserId());

        // 登录超时定时器：10s 内没收到 LOGIN_ACK 则关闭连接
        loginTimeoutFuture = eventLoopGroup.next().schedule(() -> {
            if (!loggedIn) {
                log.warn("Login timeout for userId={}, closing channel", config.getUserId());
                channel.close();
            }
        }, config.getLoginTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 收到 LOGIN_ACK 时调用（由 ClientMessageHandler 触发）。
     * 登录成功后启动心跳。
     */
    public void onLoginResult(boolean success) {
        this.loggedIn = success;

        // 取消登录超时定时器
        if (loginTimeoutFuture != null) {
            loginTimeoutFuture.cancel(false);
        }

        if (success) {
            log.info("Login success: userId={}", config.getUserId());
            startHeartbeat();
        } else {
            log.error("Login failed: userId={}", config.getUserId());
        }

        // 通知外部回调
        Consumer<Boolean> cb = this.loginCallback;
        if (cb != null) {
            cb.accept(success);
        }
    }

    // ==================== Heartbeat ====================

    /**
     * 启动心跳定时器。
     * 参考 RocketMQ：
     *   scheduleAtFixedRate(0, heartbeatBrokerInterval=30s)
     *   → sendHeartbeatToAllBrokerWithLock()
     */
    private void startHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }

        heartbeatScheduler = IMExecutors.newScheduledExecutor("im-client-hb", 1);

        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat();
            } catch (Throwable e) {
                log.error("Heartbeat error", e);
            }
        }, 0, config.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);

        log.info("Heartbeat started: interval={}s", config.getHeartbeatIntervalSeconds());
    }

    /**
     * 发送心跳消息。
     * 参考 RocketMQ 的 sendHeartbeatToBroker()。
     */
    private void sendHeartbeat() {
        Channel channel = getChannel();
        if (channel == null || !channel.isActive()) {
            log.warn("Heartbeat skipped: channel not available");
            return;
        }

        IMCommand heartbeat = new IMCommand(CommandType.HEARTBEAT);
        heartbeat.putHeader("userId", config.getUserId());
        channel.writeAndFlush(heartbeat);
    }

    // ==================== Channel Close ====================

    /**
     * 关闭 Channel 并从 channelTables 移除。
     * 参考 RocketMQ NettyRemotingClient.closeChannel()。
     */
    public void closeChannel(Channel channel) {
        if (channelWrapper != null && channelWrapper.tryClose(channel)) {
            log.info("Channel closed: addr={}, id={}", config.getServerAddress(), channel.id());
            // 不设置 channelWrapper = null，保持 wrapper 引用以便 reconnect
        }
    }

    // ==================== ACK Handling ====================

    /**
     * 尝试将收到的消息与 PendingAcknowledgement 配对。
     * 如果是 ACK 类型且对应 future 存在，complete 掉它。
     *
     * @return true = 消息已被 ACK 消费，false = 非 ACK 消息
     */
    public boolean tryAck(IMCommand msg) {
        // 判断是否为 ACK 类型
        if (isAckType(msg.getType())) {
            pendingAcknowledgementManager.onAckReceived(msg);
            return true;
        }
        return false;
    }

    private static boolean isAckType(CommandType type) {
        return switch (type) {
            case LOGIN_ACK, HEARTBEAT_ACK, SINGLE_CHAT_ACK, GROUP_CHAT_ACK, ERROR -> true;
            default -> false;
        };
    }

    // ==================== Send ====================

    /**
     * 单向发送。
     */
    public void send(IMCommand command) {
        Channel channel = getChannel();
        if (channel != null) {
            channel.writeAndFlush(command);
        } else {
            log.warn("Send failed: channel not available");
        }
    }

    /**
     * 发送并等待 ACK。
     */
    public CompletableFuture<IMCommand> sendAndAck(IMCommand command, long timeoutMs) {
        Channel channel = getChannel();
        if (channel == null) {
            CompletableFuture<IMCommand> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("Channel not available"));
            return f;
        }

        CompletableFuture<IMCommand> future = new CompletableFuture<>();
        pendingAcknowledgementManager.register(command.getSeqId(), future, timeoutMs);

        channel.writeAndFlush(command).addListener(f -> {
            if (!f.isSuccess() && !future.isDone()) {
                pendingAcknowledgementManager.onAckReceived(command);
                future.completeExceptionally(f.cause());
            }
        });

        return future;
    }

    // ==================== Callbacks ====================

    /**
     * 注册登录结果回调。
     */
    public void setLoginCallback(Consumer<Boolean> callback) {
        this.loginCallback = callback;
    }

    /**
     * 注册消息回调（收到其他用户的消息）。
     */
    public void setMessageCallback(ClientMessageHandler.MessageCallback callback) {
        this.messageHandler.setCallback(callback);
    }
}
