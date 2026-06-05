package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IAuthenticator;
import com.im.api.IConnectionSession;
import com.im.api.ImHeaders;
import com.im.api.IRouteTable;
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
    private final IRouteTable routeTable;
    private final String localNodeId;

    public HeartbeatHandler(HeartbeatUseCase heartbeatUseCase, ISessionManager sessionManager,
                            IAuthenticator authenticator) {
        this(heartbeatUseCase, sessionManager, authenticator, null, null);
    }

    public HeartbeatHandler(HeartbeatUseCase heartbeatUseCase, ISessionManager sessionManager,
                            IAuthenticator authenticator, IRouteTable routeTable, String localNodeId) {
        this.heartbeatUseCase = heartbeatUseCase;
        this.sessionManager = sessionManager;
        this.authenticator = authenticator;
        this.routeTable = routeTable;
        this.localNodeId = localNodeId;
    }

    @Override
    public Object handle(ApiRequest req) {
        String connectionId = req.attribute("_connectionId");
        if (connectionId != null) {
            IConnectionSession session = sessionManager.getByConnectionId(connectionId);
            if (session != null) {
                session.touch();
                if (session.isAuthenticated()) {
                    heartbeatUseCase.execute(session.getUserId(), session.getPlatformId(), session.getSessionId());
                } else {
                    bindSessionFromToken(req, connectionId);
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

    private void bindSessionFromToken(ApiRequest req, String connectionId) {
        if (authenticator == null || routeTable == null || localNodeId == null) {
            return;
        }
        String token = req.header(ImHeaders.AUTHORIZATION);
        if (token == null || token.isBlank()) {
            return;
        }
        if (token.startsWith(ImHeaders.BEARER_PREFIX)) {
            token = token.substring(ImHeaders.BEARER_PREFIX.length()).trim();
        }

        try {
            String userId = authenticator.authenticate(token);
            int platformId = req.getInt("platformId", com.im.api.PlatformID.WEB);
            sessionManager.bindUser(connectionId, userId, platformId);
            IConnectionSession bound = sessionManager.getByConnectionId(connectionId);
            if (bound == null || !bound.isAuthenticated()) {
                log.warn("Heartbeat token binding rejected: userId={}, platform={}", userId, platformId);
                return;
            }
            routeTable.online(userId, localNodeId, bound.getPlatformId(), bound.getSessionId());
            routeTable.setOnline(userId, bound.getPlatformId());
            log.info("Heartbeat restored online route: userId={}, platform={}, session={}",
                    userId, bound.getPlatformId(), bound.getSessionId());
        } catch (Exception e) {
            log.warn("Heartbeat token binding failed: {}", e.getMessage());
        }
    }
}
