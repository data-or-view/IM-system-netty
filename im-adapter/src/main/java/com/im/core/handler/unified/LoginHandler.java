package com.im.core.handler.unified;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.ApiRequest;
import com.im.api.IConnectionSession;
import com.im.api.IMCommand;
import com.im.api.ISessionManager;
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

    public LoginHandler(LoginUseCase loginUseCase, ISessionManager sessionManager) {
        this.loginUseCase = loginUseCase;
        this.sessionManager = sessionManager;
    }

    @Override
    public Object handle(ApiRequest req) {
        String userId = req.getString("userId");
        if (userId == null || userId.isBlank()) {
            throw new ImException(ImErrorCode.BAD_REQUEST, "userId is required");
        }

        int platformId = req.getInt("platformId", 0);

        // 业务：签发 token、注册路由、拉取离线
        LoginUseCase.LoginResult result = loginUseCase.execute(userId, platformId, 0);

        // 绑定 session（需要 Channel）
        Channel channel = req.attribute("_channel");
        if (channel != null) {
            IConnectionSession session = sessionManager.getByChannel(channel);
            if (session != null) {
                session.authenticate(userId, platformId);
            }
            sessionManager.bindUser(channel, userId);
        }

        // 投递离线消息（通过 Channel 直接写 WS 帧）
        if (channel != null && result.offlineMessages() != null && !result.offlineMessages().isEmpty()) {
            for (IMCommand offlineMsg : result.offlineMessages()) {
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
                "platformId", platformId);
    }
}
