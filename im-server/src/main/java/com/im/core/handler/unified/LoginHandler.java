package com.im.core.handler.unified;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.ISessionManager;
import com.im.api.Message;
import com.im.api.RequestHandler;
import com.im.common.enums.ImErrorCode;
import com.im.common.exception.ImException;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import com.im.core.usecase.LoginUseCase;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 登录 handler（仅 WS）。
 *
 * <p>需要访问 Netty Channel 进行 session 绑定和离线消息投递，
 * Channel 由 {@code WsRequestAdapter} 注入到 request attributes 的 {@code _channel} 键。</p>
 */
public class LoginHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);
    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    private final LoginUseCase loginUseCase;
    private final ISessionManager sessionManager;
    private final IRouteTable routeTable;
    private final String localNodeId;

    public LoginHandler(LoginUseCase loginUseCase, ISessionManager sessionManager,
                        IRouteTable routeTable, String localNodeId) {
        this.loginUseCase = loginUseCase;
        this.sessionManager = sessionManager;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
    }

    @Override
    public Object handle(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null || userId.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        }

        int platformId = req.getInt("platformId", 0);

        // ① 签发 token + 拉取离线消息（不注册路由）
        LoginUseCase.LoginResult result = loginUseCase.execute(userId, platformId, 0);

        // ② 绑定 session（会触发多端登录策略检查，可能踢旧 session）
        //    _channel 由 WsRequestAdapter 注入，HTTP 场景没有 channel 跳过 session 绑定
        Channel channel = req.attribute("_channel");
        if (channel != null) {
            IConnectionSession session = sessionManager.getByChannel(channel);
            if (session != null) {
                session.authenticate(userId, platformId);
            }
            sessionManager.bindUser(channel, userId);

            // ③ 绑定成功后注册路由（先 bindUser 后注册，
            //    避免被踢旧 session 的 channelInactive 清理逻辑误删新路由）
            if (routeTable != null) {
                routeTable.online(userId, localNodeId);
                routeTable.setOnline(userId, platformId);
            }
        }

        // 投递离线消息（仅 WS 场景有 channel）
        if (channel != null && result.offlineMessages() != null && !result.offlineMessages().isEmpty()) {
            for (Message offlineMsg : result.offlineMessages()) {
                try {
                    String json = MAPPER.writeValueAsString(offlineMsg.toJsonMap());
                    channel.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.warn("Failed to serialize offline message for user {}", userId, e);
                }
            }
            log.info("Delivered {} offline messages to user {}", result.offlineMessages().size(), userId);
        }

        log.info("User logged in: userId={}, platform={}",
                userId, com.im.api.PlatformID.name(platformId));

        return Map.of("status", "OK",
                "token", result.token() != null ? result.token() : "",
                "refreshToken", result.refreshToken() != null ? result.refreshToken() : "",
                "expiresIn", 7200,
                "platformId", platformId);
    }
}
