package com.im.core.handler;

import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.core.PendingAcknowledgementManager;
import com.im.core.util.IMExecutors;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

/**
 * 连接生命周期事件处理器，参考 RocketMQ 的 NettyConnectManageHandler。
 *
 * 职责（参考 OpenIM MsgGateway）：
 *   channelActive   → 创建 Session，注册到本地会话表
 *   channelInactive → 清理 Session → 路由表下线 → failFast pending ACKs
 *   exceptionCaught → 异常处理
 *
 * 断线清理通过虚拟线程执行器异步执行，不阻塞 IO 线程。
 *
 * 路由表下线对应 OpenIM 的 UserOffline 流程：
 *   断开 → Client 清理 → userMap 移除 → 连接关闭事件传播
 */
@ChannelHandler.Sharable
public class ConnectionEventHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionEventHandler.class);

    private final ISessionManager sessionManager;
    private final PendingAcknowledgementManager pendingAcknowledgementManager;
    private final IRouteTable routeTable;
    private final String localNodeId;
    private final ExecutorService eventExecutor;

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
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        sessionManager.createSession(ctx.channel());
        log.info("Channel active: remote={}", ctx.channel().remoteAddress());
        ctx.fireChannelActive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idleEvent
                && idleEvent.state() == IdleState.ALL_IDLE) {
            log.warn("Connection idle timeout, closing: remote={}", ctx.channel().remoteAddress());
            // 先触发 channelInactive 清理流程
            channelInactive(ctx);
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        eventExecutor.submit(() -> {
            IConnectionSession session = sessionManager.removeSession(ctx.channel());
            // 路由表下线
            if (session != null && session.getUserId() != null && routeTable != null) {
                String userId = session.getUserId();
                routeTable.offline(userId, localNodeId);
                log.info("Route unregistered: userId={}, node={}", userId, localNodeId);
            }
            pendingAcknowledgementManager.failFastAll();
            log.info("Channel inactive and cleaned: remote={}", ctx.channel().remoteAddress());
        });
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception: remote={}", ctx.channel().remoteAddress(), cause);
        eventExecutor.submit(() -> {
            IConnectionSession session = sessionManager.removeSession(ctx.channel());
            if (session != null && session.getUserId() != null && routeTable != null) {
                routeTable.offline(session.getUserId(), localNodeId);
            }
            pendingAcknowledgementManager.failFastAll();
        });
        ctx.close();
    }

    public void shutdown() {
        eventExecutor.shutdown();
    }
}
