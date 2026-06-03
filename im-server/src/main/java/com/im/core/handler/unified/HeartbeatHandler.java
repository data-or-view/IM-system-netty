package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IAuthenticator;
import com.im.api.IConnectionSession;
import com.im.api.ISessionManager;
import com.im.api.RequestHandler;
import com.im.core.usecase.HeartbeatUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 心跳 handler（仅 WS）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>更新 session 活跃时间（防止被空闲扫描踢下线）</li>
 *   <li>更新路由表在线状态</li>
 *   <li>支持 token 续期：客户端可携带 {@code refreshToken} 参数，
 *       服务端自动换发新的 access token，必要时轮换 refresh token</li>
 * </ul>
 */
public class HeartbeatHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final HeartbeatUseCase heartbeatUseCase;
    private final ISessionManager sessionManager;
    private final IAuthenticator authenticator;

    public HeartbeatHandler(HeartbeatUseCase heartbeatUseCase, ISessionManager sessionManager,
                            IAuthenticator authenticator) {
        this.heartbeatUseCase = heartbeatUseCase;
        this.sessionManager = sessionManager;
        this.authenticator = authenticator;
    }

    @Override
    public Object handle(ApiRequest req) {
        String connectionId = req.attribute("_connectionId");
        if (connectionId != null) {
            IConnectionSession session = sessionManager.getByConnectionId(connectionId);
            if (session != null) {
                session.touch();
                if (session.isAuthenticated()) {
                    heartbeatUseCase.execute(session.getUserId(), session.getPlatformId());
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "OK");
        result.put("timestamp", System.currentTimeMillis());

        // 双 token 续期：客户端在心跳中附带 refreshToken
        String refreshToken = req.getString("refreshToken");
        if (refreshToken != null && !refreshToken.isBlank() && authenticator != null) {
            try {
                IAuthenticator.TokenRefreshResult refreshResult =
                        authenticator.refreshAccessToken(refreshToken);
                if (refreshResult != null) {
                    result.put("token", refreshResult.accessToken());
                    if (refreshResult.hasNewRefreshToken()) {
                        result.put("refreshToken", refreshResult.refreshToken());
                    }
                }
            } catch (Exception e) {
                log.warn("Heartbeat token refresh failed", e);
            }
        }

        return result;
    }
}
