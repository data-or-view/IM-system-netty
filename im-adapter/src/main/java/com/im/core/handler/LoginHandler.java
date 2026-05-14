package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ISessionManager;
import com.im.api.PlatformID;
import com.im.core.usecase.LoginUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class LoginHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    private final LoginUseCase loginUseCase;
    private final ISessionManager sessionManager;

    public LoginHandler(LoginUseCase loginUseCase, ISessionManager sessionManager) {
        this.loginUseCase = loginUseCase;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("userId");
        if (userId == null || userId.isBlank()) {
            sendError(ctx, msg, "userId is required");
            return;
        }

        int platformId = PlatformID.DEFAULT;
        String pfHeader = msg.getHeader("_pf");
        if (pfHeader != null && !pfHeader.isBlank()) {
            try {
                platformId = Integer.parseInt(pfHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid platformId header: {}, using default", pfHeader);
            }
        }

        // 业务：签发 token、注册路由、拉取离线
        LoginUseCase.LoginResult result = loginUseCase.execute(userId, platformId, 0);

        // 绑定 session
        IConnectionSession session = sessionManager.getByChannel(ctx.channel());
        if (session != null) {
            session.authenticate(userId, platformId);
        }
        sessionManager.bindUser(ctx.channel(), userId);

        // 投递离线消息
        if (result.offlineMessages() != null && !result.offlineMessages().isEmpty()) {
            for (IMCommand offlineMsg : result.offlineMessages()) {
                ctx.writeAndFlush(offlineMsg);
            }
            log.info("Delivered {} offline messages to user {}", result.offlineMessages().size(), userId);
        }

        // 回复 ACK
        IMCommand ack = msg.createAcknowledgement(CommandType.LOGIN_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("_pf", String.valueOf(platformId));
        if (result.token() != null) {
            ack.putHeader("token", result.token());
        }
        ctx.writeAndFlush(ack);

        log.info("User logged in: userId={}, platform={}, remote={}",
                userId, PlatformID.name(platformId), ctx.channel().remoteAddress());
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.LOGIN);
    }
}
