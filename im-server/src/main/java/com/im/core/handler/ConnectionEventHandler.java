package com.im.core.handler;

import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.core.dispatcher.PendingAcknowledgementManager;
import com.im.common.util.IMExecutors;
import com.im.core.observability.LogEvents;
import com.im.core.observability.LogFields;
import com.im.core.observability.StructuredLog;
import com.im.core.session.NettyConnectionRef;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 连接生命周期事件处理器，参考 RocketMQ 的 NettyConnectManageHandler。
 *
 * 职责（参考 OpenIM MsgGateway）：
 *   channelActive   → 创建 Session，注册到本地会话表
 *   channelInactive → 清理 Session → 路由表下线 → 在线状态移除 → failFast pending ACKs
 *   exceptionCaught → 异常处理
 *
 * 断线清理通过虚拟线程执行器异步执行，不阻塞 IO 线程。
 *
 * 路由表下线对应 OpenIM 的 UserOffline 流程：
 *   断开 → Client 清理 → userMap 移除 → 连接关闭事件传播 → Redis online 状态移除
 */
@ChannelHandler.Sharable
public class ConnectionEventHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionEventHandler.class);

    private final ISessionManager sessionManager;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;
    private final IRouteTable routeTable;
    private final String localNodeId;
    private final ExecutorService eventExecutor;
    private final ScheduledExecutorService routeRenewExecutor;
    private final AtomicBoolean renewalRunning = new AtomicBoolean(false);

    public ConnectionEventHandler(ISessionManager sessionManager, PendingAcknowledgementManager pendingAcknowledgementManager) {
        this(sessionManager, pendingAcknowledgementManager, null, "local");
    }

    public ConnectionEventHandler(ISessionManager sessionManager, PendingAcknowledgementManager pendingAcknowledgementManager,
                                  IRouteTable routeTable, String localNodeId) {
        this.sessionManager = sessionManager;
        this.pendingAcknowledgementManager = pendingAcknowledgementManager;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
        this.eventExecutor = IMExecutors.newVirtualThreadExecutor("im-event");
        this.routeRenewExecutor = IMExecutors.newScheduledExecutor("im-route-renew", 1);
        startRouteRenewal();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        sessionManager.createSession(new NettyConnectionRef(ctx.channel()));
        log.info(StructuredLog.event(LogEvents.CONNECTION_OPENED,
                LogFields.NODE_ID, localNodeId,
                LogFields.PROTOCOL, "ws",
                LogFields.CONNECTION_ID, NettyConnectionRef.connectionId(ctx.channel()),
                LogFields.CLIENT_IP, ctx.channel().remoteAddress()));
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idleEvent
                && idleEvent.state() == IdleState.ALL_IDLE) {
            log.warn(StructuredLog.event(LogEvents.CONNECTION_CLOSED,
                    LogFields.NODE_ID, localNodeId,
                    LogFields.PROTOCOL, "ws",
                    LogFields.CONNECTION_ID, NettyConnectionRef.connectionId(ctx.channel()),
                    LogFields.CLIENT_IP, ctx.channel().remoteAddress(),
                    LogFields.REASON, "idle_timeout"));
            // 在 close 前强制清理（close 会触发 channelInactive 二次入队），
            // 二次 failFastAll 是幂等的，但 session.remove 第二次返回 null 被跳过。
            cleanupSession(ctx);
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        eventExecutor.execute(() -> safeCleanupSession(ctx, "channelInactive"));
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error(StructuredLog.event(LogEvents.CONNECTION_EXCEPTION,
                LogFields.NODE_ID, localNodeId,
                LogFields.PROTOCOL, "ws",
                LogFields.CONNECTION_ID, NettyConnectionRef.connectionId(ctx.channel()),
                LogFields.CLIENT_IP, ctx.channel().remoteAddress(),
                LogFields.EXCEPTION_CLASS, cause.getClass().getSimpleName()), cause);
        eventExecutor.execute(() -> safeCleanupSession(ctx, "exceptionCaught"));
        ctx.close();
    }

    private void safeCleanupSession(ChannelHandlerContext ctx, String trigger) {
        try {
            cleanupSession(ctx);
        } catch (Exception e) {
            log.error(StructuredLog.event(LogEvents.CONNECTION_EXCEPTION,
                    LogFields.NODE_ID, localNodeId,
                    LogFields.PROTOCOL, "ws",
                    LogFields.CONNECTION_ID, NettyConnectionRef.connectionId(ctx.channel()),
                    LogFields.CLIENT_IP, ctx.channel().remoteAddress(),
                    LogFields.REASON, trigger,
                    LogFields.EXCEPTION_CLASS, e.getClass().getSimpleName()), e);
        }
    }

    /** 提取 session 清理逻辑，channelInactive / exceptionCaught / idle 三处复用。 */
    private void cleanupSession(ChannelHandlerContext ctx) {
        IConnectionSession session = sessionManager.removeSession(NettyConnectionRef.connectionId(ctx.channel()));
        if (session != null && session.getUserId() != null && routeTable != null) {
            String userId = session.getUserId();
            int platformId = session.getPlatformId();
            routeTable.offline(userId, localNodeId, platformId, session.getSessionId());
            log.info(StructuredLog.event(LogEvents.SESSION_CLEANED,
                    LogFields.NODE_ID, localNodeId,
                    LogFields.USER_ID, userId,
                    LogFields.PLATFORM_ID, platformId,
                    LogFields.SESSION_ID, session.getSessionId(),
                    LogFields.CONNECTION_ID, NettyConnectionRef.connectionId(ctx.channel())));
        }
        pendingAcknowledgementManager.failFastAll();
    }

    private void startRouteRenewal() {
        if (routeTable == null) {
            return;
        }
        routeRenewExecutor.scheduleWithFixedDelay(this::safeRenewLocalRoutes,
                30, 30, TimeUnit.SECONDS);
    }

    private void safeRenewLocalRoutes() {
        if (!renewalRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            renewLocalRoutes();
        } catch (Exception e) {
            log.warn("Local route renewal failed: node={}", localNodeId, e);
        } finally {
            renewalRunning.set(false);
        }
    }

    private void renewLocalRoutes() {
        for (IConnectionSession session : sessionManager.allSessions()) {
            if (!session.isAuthenticated()
                    || session.getUserId() == null
                    || !session.getConnection().isActive()) {
                continue;
            }
            routeTable.renewOnline(session.getUserId(), session.getPlatformId(), session.getSessionId());
        }
    }

    public void shutdown() {
        eventExecutor.shutdown();
        routeRenewExecutor.shutdown();
    }
}
