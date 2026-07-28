package com.im.core.delivery;

import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.Message;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClusterDeliveryHandlerTest {

    @Test
    void removesTargetRouteWhenSessionIsMissingOnReceivingNode() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel activeOtherSession = new EmbeddedChannel();
        IConnectionSession session = sessionManager.createSession(new NettyConnectionRef(activeOtherSession));
        sessionManager.bindUser(session.getConnection().connectionId(), "u2", PlatformID.IOS);
        TestRouteTable routeTable = new TestRouteTable();
        ClusterDeliveryHandler handler = new ClusterDeliveryHandler(
                sessionManager, routeTable, "node-b", "lease-b");

        handler.handle(com.im.api.ClusterMessage.fromMessage(
                "node-a",
                Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1),
                new RouteBinding("u2", "node-b", PlatformID.IOS, "missing-session", 0,
                        "lease-b", "generation-b")));

        assertNull(activeOtherSession.readOutbound(), "non-target sessions must not receive stale targeted messages");
        assertEquals(List.of("u2|node-b|1|missing-session|lease-b|generation-b"), routeTable.offlineCalls);
    }

    @Test
    void removesExactTargetRouteWhenUserHasNoLocalSessions() {
        TestRouteTable routeTable = new TestRouteTable();
        ClusterDeliveryHandler handler = new ClusterDeliveryHandler(
                new SessionManager(), routeTable, "node-b", "lease-b");

        handler.handle(com.im.api.ClusterMessage.fromMessage(
                "node-a",
                Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1),
                new RouteBinding("u2", "node-b", PlatformID.IOS, "missing-session", 0,
                        "lease-b", "generation-b")));

        assertEquals(List.of("u2|node-b|1|missing-session|lease-b|generation-b"), routeTable.offlineCalls);
    }

    private static final class TestRouteTable implements IRouteTable {
        private final CopyOnWriteArrayList<String> offlineCalls = new CopyOnWriteArrayList<>();

        @Override public void online(String userId, String nodeId, int platformId, String sessionId) {}

        @Override
        public void offline(String userId, String nodeId, int platformId, String sessionId) {
            offlineCalls.add(userId + "|" + nodeId + "|" + platformId + "|" + sessionId);
        }

        @Override
        public void offline(RouteBinding binding) {
            offlineCalls.add(binding.userId() + "|" + binding.nodeId() + "|" + binding.platformId()
                    + "|" + binding.sessionId() + "|" + binding.nodeIncarnation() + "|" + binding.generation());
        }

        @Override public RouteNode lookup(String userId) { return null; }

        @Override public List<RouteNode> lookupAll(String userId) { return List.of(); }

        @Override public List<RouteBinding> lookupAllBindings(String userId) { return List.of(); }

        @Override public void setOnline(String userId, int platformId) {}

        @Override public void setOffline(String userId, int platformId) {}

        @Override public List<Integer> getOnlinePlatforms(String userId) { return List.of(); }

        @Override public Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) {
            return Map.of();
        }

        @Override public void renewOnline(String userId, int platformId) {}
    }
}
