package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ISessionManager;
import com.im.core.usecase.HeartbeatUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class HeartbeatHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final HeartbeatUseCase heartbeatUseCase;
    private final ISessionManager sessionManager;

    public HeartbeatHandler(HeartbeatUseCase heartbeatUseCase, ISessionManager sessionManager) {
        this.heartbeatUseCase = heartbeatUseCase;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        IConnectionSession session = sessionManager.getByChannel(ctx.channel());
        if (session != null) {
            session.touch();
            if (session.isAuthenticated()) {
                heartbeatUseCase.execute(session.getUserId(), session.getPlatformId());
            }
        }

        IMCommand ack = msg.createAcknowledgement(CommandType.HEARTBEAT_ACK);
        ctx.writeAndFlush(ack);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.HEARTBEAT);
    }
}
