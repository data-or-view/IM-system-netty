package com.im.core.handler;

import com.im.api.CommandType;
import com.im.api.IAuthenticator;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.IMessageHandler;
import com.im.api.IMessageStore;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.PlatformID;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 登录处理器。
 *
 * 流程（参考 OpenIM MsgGateway 的 UserLogin）：
 *   ① 解析 userId + platformId（_pf header，可选）
 *   ② 签发 token（IAuthenticator.issueToken）
 *   ③ sessionManager.bindUser → 绑定 userId 到 channel
 *   ④ routeTable.online → 注册节点路由
 *   ⑤ routeTable.setOnline → 标记 platform 在线
 *   ⑥ messageStore.pullOffline → 推送离线消息
 *   ⑦ 回复 LOGIN_ACK（含 token）
 */
public class LoginHandler implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);

    /** token 有效期：30 天 */
    private static final Duration TOKEN_TTL = Duration.ofDays(30);

    private final ISessionManager sessionManager;
    private final IMessageStore messageStore;
    private final IRouteTable routeTable;
    private final String localNodeId;
    private final IAuthenticator authenticator;

    public LoginHandler(ISessionManager sessionManager) {
        this(sessionManager, null, null, "local", null);
    }

    public LoginHandler(ISessionManager sessionManager, IMessageStore messageStore) {
        this(sessionManager, messageStore, null, "local", null);
    }

    public LoginHandler(ISessionManager sessionManager, IMessageStore messageStore,
                        IRouteTable routeTable, String localNodeId) {
        this(sessionManager, messageStore, routeTable, localNodeId, null);
    }

    public LoginHandler(ISessionManager sessionManager, IMessageStore messageStore,
                        IRouteTable routeTable, String localNodeId,
                        IAuthenticator authenticator) {
        this.sessionManager = sessionManager;
        this.messageStore = messageStore;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
        this.authenticator = authenticator;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, IMCommand msg) {
        String userId = msg.getHeader("userId");
        if (userId == null || userId.isBlank()) {
            sendError(ctx, msg, "userId is required");
            return;
        }

        // ① 解析 platformId（可选，默认 Web）
        int platformId = PlatformID.DEFAULT;
        String pfHeader = msg.getHeader("_pf");
        if (pfHeader != null && !pfHeader.isBlank()) {
            try {
                platformId = Integer.parseInt(pfHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid platformId header: {}, using default", pfHeader);
            }
        }

        // TODO: 接入真实密码/验证码验证（当前是直通模式）

        // ② 签发 token
        String token = null;
        if (authenticator != null) {
            token = authenticator.issueToken(userId, TOKEN_TTL);
        }

        // ③ 绑定 userId 到当前 channel（含 platformId）
        IConnectionSession session = sessionManager.getByChannel(ctx.channel());
        if (session != null) {
            session.authenticate(userId, platformId);
        }
        sessionManager.bindUser(ctx.channel(), userId);

        // ④ 注册节点路由
        if (routeTable != null) {
            routeTable.online(userId, localNodeId);
            // ⑤ 标记 platform 在线
            routeTable.setOnline(userId, platformId);
            log.info("Route registered: userId={}, node={}, platform={}", userId, localNodeId, PlatformID.name(platformId));
        }

        // ⑥ 拉取离线消息并投递
        if (messageStore != null) {
            List<IMCommand> offline = messageStore.pullOffline(userId, 100);
            for (IMCommand offlineMsg : offline) {
                ctx.writeAndFlush(offlineMsg);
            }
            if (!offline.isEmpty()) {
                log.info("Delivered {} offline messages to user {}", offline.size(), userId);
            }
        }

        // ⑦ 回复 LOGIN_ACK（含 token + platformId）
        IMCommand ack = msg.createAcknowledgement(CommandType.LOGIN_ACK);
        ack.putHeader("status", "OK");
        ack.putHeader("_pf", String.valueOf(platformId));
        if (token != null) {
            ack.putHeader("token", token);
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
