package com.im.core.handler.unified;

import com.im.api.ApiRequest;
import com.im.api.ClusterMessage;
import com.im.api.IAuthenticator;
import com.im.api.TokenRefreshResult;
import com.im.api.IClusterMessageBus;
import com.im.api.IMessageStore;
import com.im.api.IRouteTable;
import com.im.api.Message;
import com.im.api.MultiLoginStrategy;
import com.im.api.Operation;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.common.exception.ConflictException;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import com.im.core.usecase.LoginUseCase;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginHandlerTest {

    @Test
    void sendsKickSessionCommandForRemoteSamePlatformSession() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.setLoginStrategy(MultiLoginStrategy.SAME_TERM_KICK);
        RecordingRouteTable routeTable = new RecordingRouteTable();
        routeTable.bindings.add(new RouteBinding("u1", "node-b", PlatformID.IOS, "old-session", 0));
        RecordingClusterMessageBus clusterMessageBus = new RecordingClusterMessageBus();
        LoginHandler handler = new LoginHandler(
                new LoginUseCase(new StubAuthenticator(), new EmptyMessageStore()),
                sessionManager,
                routeTable,
                "node-a",
                clusterMessageBus,
                MultiLoginStrategy.SAME_TERM_KICK);

        EmbeddedChannel channel = new EmbeddedChannel();
        NettyConnectionRef connection = new NettyConnectionRef(channel);
        sessionManager.createSession(connection);
        ApiRequest request = new ApiRequest(
                Operation.LOGIN,
                Map.of("userId", "u1", "platformId", PlatformID.IOS),
                Map.of(),
                null,
                null);
        request.setAttribute("_connectionId", connection.connectionId());

        handler.handle(request);

        assertEquals(1, clusterMessageBus.sent.size());
        ClusterMessage message = clusterMessageBus.sent.getFirst();
        assertEquals("node-b", clusterMessageBus.targets.getFirst());
        assertEquals("u1", message.getCommand().userId());
        assertEquals(PlatformID.IOS, message.getCommand().platformId());
        assertEquals("old-session", message.getCommand().sessionId());
        assertTrue(routeTable.onlineCalls.stream().anyMatch(call -> call.contains("node-a")));

        sessionManager.clear();
    }

    @Test
    void rejectNewRefusesExistingRemoteRouteBeforeRegisteringNewRoute() {
        SessionManager sessionManager = new SessionManager();
        sessionManager.setLoginStrategy(MultiLoginStrategy.REJECT_NEW);
        RecordingRouteTable routeTable = new RecordingRouteTable();
        routeTable.bindings.add(new RouteBinding("u1", "node-b", PlatformID.IOS, "old-session", 0));
        LoginHandler handler = new LoginHandler(
                new LoginUseCase(new StubAuthenticator(), new EmptyMessageStore()),
                sessionManager,
                routeTable,
                "node-a",
                new RecordingClusterMessageBus(),
                MultiLoginStrategy.REJECT_NEW);

        EmbeddedChannel channel = new EmbeddedChannel();
        NettyConnectionRef connection = new NettyConnectionRef(channel);
        sessionManager.createSession(connection);
        ApiRequest request = new ApiRequest(
                Operation.LOGIN,
                Map.of("userId", "u1", "platformId", PlatformID.IOS),
                Map.of(),
                null,
                null);
        request.setAttribute("_connectionId", connection.connectionId());

        assertThrows(ConflictException.class, () -> handler.handle(request));
        assertTrue(routeTable.onlineCalls.isEmpty());

        sessionManager.clear();
    }

    private static final class RecordingRouteTable implements IRouteTable {
        private final List<RouteBinding> bindings = new ArrayList<>();
        private final List<String> onlineCalls = new ArrayList<>();

        @Override
        public void online(String userId, String nodeId, int platformId, String sessionId) {
            onlineCalls.add(userId + "|" + nodeId + "|" + platformId + "|" + sessionId);
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
            return bindings.stream().map(binding -> binding.toRouteNode("node-a")).toList();
        }

        @Override
        public List<RouteBinding> lookupAllBindings(String userId) {
            return bindings;
        }

        @Override
        public void setOnline(String userId, int platformId) {
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

    private static final class RecordingClusterMessageBus implements IClusterMessageBus {
        private final List<ClusterMessage> sent = new ArrayList<>();
        private final List<String> targets = new ArrayList<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean sendToNode(ClusterMessage msg, String targetNodeId) {
            sent.add(msg);
            targets.add(targetNodeId);
            return true;
        }

        @Override
        public void broadcast(ClusterMessage msg) {
        }

        @Override
        public void subscribe(String topic, com.im.api.ClusterMessageHandler handler) {
        }

        @Override
        public void unsubscribe(String topic, com.im.api.ClusterMessageHandler handler) {
        }
    }

    private static final class StubAuthenticator implements IAuthenticator {
        @Override
        public String issueToken(String userId, Duration ttl) {
            return "token-" + userId;
        }

        @Override
        public String authenticate(String token) {
            return "u1";
        }

        @Override
        public String issueRefreshToken(String userId, Duration ttl, int appManagerLevel) {
            return "refresh-" + userId;
        }

        @Override
        public TokenRefreshResult refreshAccessToken(String refreshToken) {
            return new TokenRefreshResult("token", null);
        }
    }

    private static final class EmptyMessageStore implements IMessageStore {
        @Override
        public void save(Message msg) {
        }

        @Override
        public List<Message> pullOffline(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<Message> pullBySequence(String conversationId, long startSeq, long endSeq, int limit) {
            return List.of();
        }

        @Override
        public void markDelivered(String userId, List<String> msgIds) {
        }
    }
}
