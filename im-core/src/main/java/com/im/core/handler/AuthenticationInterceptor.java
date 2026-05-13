package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IAuthenticator;
import com.im.api.IMCommand;
import com.im.api.IMInterceptor;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 认证拦截器。
 *
 * 参考 Spring MVC HandlerInterceptor + OpenIM 的 token 验证：
 *   · 登录/心跳请求不需要 token（白名单）
 *   · 其他请求必须带 Authorization 头，否则阻断
 *   · 验证通过后，将 userId 写入 IMCommand 的 fromUserId 头
 *
 * 位置（在 MessageRouterHandler 的拦截器链中的第一个）：
 *   AuthenticationInterceptor → 其他业务拦截器 → handler
 */
public class AuthenticationInterceptor implements IMInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationInterceptor.class);

    /** 不需要 token 的白名单命令 */
    private static final Set<CommandType> WHITE_LIST = Set.of(
            CommandType.LOGIN,
            CommandType.REGISTER,
            CommandType.HEARTBEAT,
            CommandType.HEARTBEAT_ACK
    );

    private static final String TOKEN_HEADER = "Authorization";

    private final IAuthenticator authenticator;

    public AuthenticationInterceptor(IAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean preHandle(ChannelHandlerContext ctx, IMCommand msg) {
        // 白名单直接放行
        if (WHITE_LIST.contains(msg.getType())) {
            return true;
        }

        // 取 token（去掉可选的 "Bearer " 前缀）
        String token = msg.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            log.warn("Request without token: type={}, seqId={}", msg.getType(), msg.getSeqId());
            return false;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        // 验证
        try {
            String userId = authenticator.authenticate(token);
            // 写入 fromUserId（后续 handler 可直接用）
            msg.putHeader("fromUserId", userId);
            msg.putHeader("_uid", userId);
            return true;
        } catch (Exception e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterComplete(ChannelHandlerContext ctx, IMCommand msg, Exception ex) {
        // 无清理逻辑
    }

    @Override
    public String name() {
        return "auth";
    }

    /** 鉴权必须最先执行 */
    @Override
    public int order() {
        return Integer.MIN_VALUE;
    }
}
