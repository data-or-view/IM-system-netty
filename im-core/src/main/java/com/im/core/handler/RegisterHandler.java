package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.ImException;
import com.im.api.IUserManager;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 注册处理器。
 *
 * 流程：
 *   ① 解析 userId + password + nickname + faceUrl
 *   ② 检查用户是否存在（存在则幂等返回 OK）
 *   ③ 调用 userManager.register() 创建用户
 *   ④ 单独设置 password 到 UserInformation
 *   ⑤ 回复 REGISTER_ACK（status = OK）
 */
public class RegisterHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);

    private final IUserManager userManager;

    public RegisterHandler(IUserManager userManager) {
        this.userManager = userManager;
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

        // 简易注册：nickname 为空则用 userId 代替
        if (nickname == null || nickname.isBlank()) {
            nickname = userId;
        }

        // 检查用户是否已存在
        if (userManager != null) {
            boolean exists = false;
            try {
                var existing = userManager.getUserInformation(userId);
                exists = existing != null;
            } catch (ImException e) {
                // NOT_FOUND 表示用户不存在 → 可以注册
                exists = false;
            } catch (Exception e) {
                // 其他异常 → 把用户 ID 视为不存在，继续注册
                log.warn("Error checking user existence, will proceed with registration: {}", e.getMessage());
                exists = false;
            }

            if (exists) {
                // 用户已存在 → 返回 OK（幂等设计）
                var existing = userManager.getUserInformation(userId);
                IMCommand ack = msg.createAcknowledgement(CommandType.REGISTER_ACK);
                ack.putHeader("status", "OK");
                ack.putHeader("userId", userId);
                ack.putHeader("nickname", existing != null && existing.getNickname() != null ? existing.getNickname() : "");
                ack.putHeader("faceUrl", existing != null && existing.getFaceUrl() != null ? existing.getFaceUrl() : "");
                ctx.writeAndFlush(ack);
                log.info("User already exists, re-register skipped: userId={}", userId);
                return;
            }

            // 注册新用户
            try {
                userManager.register(userId, nickname, faceUrl, null);
            } catch (Exception e) {
                sendError(ctx, msg, "register failed: " + e.getMessage());
                return;
            }
        }

        // 存储密码（单独设置，避免修改 IUserManager 接口）
        if (password != null && !password.isBlank()) {
            try {
                var user = userManager.getUserInformation(userId);
                if (user != null && user.getPassword() == null) {
                    user.setPassword(password);
                    log.info("Password set for userId={}", userId);
                }
            } catch (Exception e) {
                log.warn("Could not set password for userId={}: {}", userId, e.getMessage());
            }
        }

        // 回复成功
        IMCommand ack = msg.createAcknowledgement(CommandType.REGISTER_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("userId", userId);
        ack.putHeader("nickname", nickname != null ? nickname : "");
        ack.putHeader("faceUrl", faceUrl != null ? faceUrl : "");
        ctx.writeAndFlush(ack);

        log.info("User registered: userId={}, nickname={}, remote={}",
                userId, nickname, ctx.channel().remoteAddress());
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
