package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ISessionManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 心跳处理器，实现 IMessageHandler 接口。
 *
 * 收到 HEARTBEAT → 刷新 session.touch() + 回复 HEARTBEAT_ACK。
 * 心跳走独立线程池，不阻塞业务处理。
 */
public class HeartbeatHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final ISessionManager sessionManager;

    public HeartbeatHandler(ISessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        // 刷新会话活跃时间
        IConnectionSession session = sessionManager.getByChannel(ctx.channel());
        if (session != null) {
            session.touch();
        }

        // 回复 HEARTBEAT_ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.HEARTBEAT_ACK);
        ctx.writeAndFlush(ack);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.HEARTBEAT);
    }
}
