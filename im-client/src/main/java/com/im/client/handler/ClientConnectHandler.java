package com.im.client.handler;

import com.im.client.ChannelWrapper;
import com.im.client.IMClient;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端连接事件处理器，参考 RocketMQ 的 NettyConnectManageHandler。
 *
 * 事件触发流：
 *   ① connect     → "connecting to server"
 *   ② channelActive → 通道就绪，触发 LOGIN（auth）
 *   ③ channelInactive → 连接断线，移除 channelTables 中的记录
 *   ④ userEventTriggered → idle 超时 → 关闭 channel（触发重建）
 *   ⑤ exceptionCaught   → 异常处理
 *
 * 注意：channelInactive 时不做自动重连 ——
 *       重连由业务方（使用 getChannel() 时发现 isOK=false）或 HeartbeatSender 触发。
 *       RocketMQ 也是同样的模式：不在 handler 里做重连，而是在 getAndCreateChannel 时重建。
 */
public class ClientConnectHandler extends ChannelDuplexHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientConnectHandler.class);

    private final IMClient client;

    public ClientConnectHandler(IMClient client) {
        this.client = client;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, java.net.SocketAddress remoteAddress,
                        java.net.SocketAddress localAddress, ChannelPromise promise) throws Exception {
        log.info("Connecting to {}", remoteAddress);
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Channel active: remote={}, channelId={}",
                ctx.channel().remoteAddress(), ctx.channel().id());
        // 连接就绪 → 触发登录
        client.onChannelActive(ctx.channel());
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Channel inactive: {}, id={}", ctx.channel().remoteAddress(), ctx.channel().id());
        // 断线 → 从 channelTables 移除
        client.closeChannel(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.ALL_IDLE) {
                log.warn("Channel idle timeout, closing: remote={}, id={}",
                        ctx.channel().remoteAddress(), ctx.channel().id());
                client.closeChannel(ctx.channel());
                ctx.close();
                return;
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception: remote={}", ctx.channel().remoteAddress(), cause);
        client.closeChannel(ctx.channel());
        ctx.close();
    }
}
