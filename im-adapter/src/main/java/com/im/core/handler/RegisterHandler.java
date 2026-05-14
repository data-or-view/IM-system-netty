package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.core.usecase.RegisterUseCase;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class RegisterHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);

    private final RegisterUseCase registerUseCase;

    public RegisterHandler(RegisterUseCase registerUseCase) {
        this.registerUseCase = registerUseCase;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("userId");
        if (userId == null || userId.isBlank()) {
            sendError(ctx, msg, "userId is required");
            return;
        }

        String nickname = msg.getHeader("nickname");
        String faceUrl = msg.getHeader("faceUrl");
        String password = msg.getHeader("password");

        if (nickname == null || nickname.isBlank()) {
            nickname = userId;
        }

        RegisterUseCase.RegisterResult result = registerUseCase.execute(userId, nickname, faceUrl, password);

        IMCommand ack = msg.createAcknowledgement(CommandType.REGISTER_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("userId", userId);
        ack.putHeader("nickname", result.nickname() != null ? result.nickname() : "");
        ack.putHeader("faceUrl", result.faceUrl() != null ? result.faceUrl() : "");
        ctx.writeAndFlush(ack);

        log.info("User registered: userId={}, nickname={}, remote={}",
                userId, result.nickname(), ctx.channel().remoteAddress());
    }

    private void sendError(ChannelHandlerContext ctx, IMCommand msg, String reason) {
        IMCommand error = msg.createAcknowledgement(CommandType.ERROR);
        error.putHeader("reason", reason);
        ctx.writeAndFlush(error);
    }

    @Override
    public Set<CommandType> supportedTypes() {
        return Set.of(CommandType.REGISTER);
    }
}
