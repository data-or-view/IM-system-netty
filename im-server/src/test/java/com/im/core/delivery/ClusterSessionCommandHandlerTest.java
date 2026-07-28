package com.im.core.delivery;

import com.im.api.ClusterCommand;
import com.im.api.ClusterMessage;
import com.im.api.IRouteTable;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterSessionCommandHandlerTest {

    @Test
    void closesOnlyMatchingSessionForKickSessionCommand() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        EmbeddedChannel desktop = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        NettyConnectionRef desktopRef = new NettyConnectionRef(desktop);

        String phoneSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        sessionManager.createSession(desktopRef);
        sessionManager.bindUser(desktopRef.connectionId(), "u1", PlatformID.WINDOWS);

        RouteBinding current = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, phoneSessionId, 0, "lease-b", "generation-b");
        BindingRouteTable routeTable = new BindingRouteTable(current);
        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(
                sessionManager, routeTable, "node-b", "lease-b");
        handler.handle(ClusterMessage.fromCommand(
                "node-a",
                ClusterCommand.kickSession(current, "SAME_TERM_KICK")));

        assertFalse(phone.isActive(), "matching session should be kicked");
        assertTrue(desktop.isActive(), "other platform session should stay online");
        assertTrue(routeTable.conditionalRemovalAttempted,
                "session logout must be claimed by exact conditional route removal");

        sessionManager.clear();
    }

    @Test
    void ignoresKickSessionCommandForReplacedBindingGeneration() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        String phoneSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        RouteBinding stale = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, phoneSessionId, 0, "lease-b", "generation-old");
        RouteBinding current = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, phoneSessionId, 0, "lease-b", "generation-new");
        BindingRouteTable routeTable = new BindingRouteTable(current);
        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(
                sessionManager, routeTable, "node-b", "lease-b");

        handler.handle(ClusterMessage.fromCommand(
                "node-a", ClusterCommand.kickSession(stale, "SAME_TERM_KICK")));

        assertTrue(phone.isActive(), "stale generation must not kick a rebound session");
        assertTrue(routeTable.conditionalRemovalAttempted,
                "stale commands must fail through the same atomic route comparison");
        sessionManager.clear();
    }

    private static final class BindingRouteTable implements IRouteTable {
        private final RouteBinding binding;
        private boolean conditionalRemovalAttempted;

        private BindingRouteTable(RouteBinding binding) {
            this.binding = binding;
        }

        @Override public void online(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public void offline(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public boolean offlineIfCurrent(RouteBinding candidate) {
            conditionalRemovalAttempted = true;
            return candidate.sameIdentity(binding);
        }
        @Override public RouteNode lookup(String userId) { return null; }
        @Override public List<RouteNode> lookupAll(String userId) { return List.of(); }
        @Override public List<RouteBinding> lookupAllBindings(String userId) { return List.of(binding); }
        @Override public void setOnline(String userId, int platformId) {}
        @Override public void setOffline(String userId, int platformId) {}
        @Override public List<Integer> getOnlinePlatforms(String userId) { return List.of(); }
        @Override public Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) { return Map.of(); }
        @Override public void renewOnline(String userId, int platformId) {}
    }
}
