package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.IAuthenticator;
import com.im.api.TokenRefreshResult;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.Operation;
import com.im.api.PlatformID;
import com.im.api.RouteNode;
import com.im.api.MultiLoginStrategy;
import com.im.common.exception.UnauthorizedException;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import com.im.core.usecase.HeartbeatUseCase;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatHandlerTest {

    @Test
    void bindsTokenAuthenticatedReconnectSessionDuringHeartbeat() {
        SessionManager sessionManager = new SessionManager();
        RecordingRouteTable routeTable = new RecordingRouteTable();
        HeartbeatHandler handler = new HeartbeatHandler(
                new HeartbeatUseCase(routeTable),
                sessionManager,
                new StubAuthenticator(),
                routeTable,
                "node-a");

        EmbeddedChannel channel = new EmbeddedChannel();
        NettyConnectionRef connection = new NettyConnectionRef(channel);
        IConnectionSession session = sessionManager.createSession(connection);
        ApiRequest request = new ApiRequest(
                Operation.HEARTBEAT,
                Map.of("platformId", PlatformID.WEB),
                Map.of("Authorization", "token-u1"),
                null,
                null);
        request.setAttribute("_connectionId", connection.connectionId());

        handler.handle(request);

        assertTrue(session.isAuthenticated());
        assertEquals("u1", session.getUserId());
        assertEquals("u1|node-a|" + PlatformID.WEB + "|" + session.getSessionId(), routeTable.lastOnline);
        assertEquals("u1|" + PlatformID.WEB, routeTable.lastSetOnline);

        sessionManager.clear();
    }

    @Test
    void heartbeatRouteRestoreHonorsRejectNewStrategy() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.setLoginStrategy(MultiLoginStrategy.REJECT_NEW);
        RecordingRouteTable routeTable = new RecordingRouteTable();
        HeartbeatHandler handler = new HeartbeatHandler(
                new HeartbeatUseCase(routeTable),
                sessionManager,
                new StubAuthenticator(),
                routeTable,
                "node-a");

        EmbeddedChannel oldChannel = new EmbeddedChannel();
        NettyConnectionRef oldConnection = new NettyConnectionRef(oldChannel);
        sessionManager.createSession(oldConnection);
        sessionManager.bindUser(oldConnection.connectionId(), "u1", PlatformID.WEB);

        EmbeddedChannel newChannel = new EmbeddedChannel();
        NettyConnectionRef newConnection = new NettyConnectionRef(newChannel);
        IConnectionSession rejectedSession = sessionManager.createSession(newConnection);
        ApiRequest request = new ApiRequest(
                Operation.HEARTBEAT,
                Map.of("platformId", PlatformID.WEB),
                Map.of("Authorization", "token-u1"),
                null,
                null);
        request.setAttribute("_connectionId", newConnection.connectionId());

        handler.handle(request);

        assertTrue(sessionManager.getSessionsByUserId("u1").stream()
                .noneMatch(session -> session.getSessionId().equals(rejectedSession.getSessionId())));
        assertEquals(false, rejectedSession.isAuthenticated());
        assertEquals(null, routeTable.lastOnline);

        sessionManager.clear();
    }

    private static final class RecordingRouteTable implements IRouteTable {
        private String lastOnline;
        private String lastSetOnline;

        @Override
        public void online(String userId, String nodeId, int platformId, String sessionId) {
            lastOnline = userId + "|" + nodeId + "|" + platformId + "|" + sessionId;
        }

        @Override
        public void offline(String userId, String nodeId, int platformId, String sessionId) {
        }

        @Override
        public RouteNode lookup(String userId) {
            return null;
        }

        @Override
        public List<RouteNode> lookupAll(String userId) {
            return List.of();
        }

        @Override
        public void setOnline(String userId, int platformId) {
            lastSetOnline = userId + "|" + platformId;
        }

        @Override
        public void setOffline(String userId, int platformId) {
        }

        @Override
        public List<Integer> getOnlinePlatforms(String userId) {
            return List.of();
        }

        @Override
        public void renewOnline(String userId, int platformId) {
        }
    }

    private static final class StubAuthenticator implements IAuthenticator {
        @Override
        public String issueToken(String userId, Duration ttl) {
            return "token-" + userId;
        }

        @Override
        public String authenticate(String token) {
            if ("token-u1".equals(token)) {
                return "u1";
            }
            throw new UnauthorizedException("invalid token");
        }

        @Override
        public String issueRefreshToken(String userId, Duration ttl, int appManagerLevel) {
            return "refresh-" + userId;
        }

        @Override
        public TokenRefreshResult refreshAccessToken(String refreshToken) {
            return new TokenRefreshResult("token-u1", null);
        }
    }
}
