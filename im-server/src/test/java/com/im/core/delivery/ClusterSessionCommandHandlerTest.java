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
    void appliesKickSessionCommandAfterHeartbeatRotatesGenerationForSameSession() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        String phoneSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        RouteBinding current = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, phoneSessionId, 0, "lease-b", "generation-old");
        BindingRouteTable routeTable = new BindingRouteTable(current);
        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(
                sessionManager, routeTable, "node-b", "lease-b");
        RouteBinding kickSnapshot = routeTable.lookupAllBindings("u1").getFirst();

        routeTable.renewCurrentBinding("generation-new");

        handler.handle(ClusterMessage.fromCommand(
                "node-a", ClusterCommand.kickSession(kickSnapshot, "SAME_TERM_KICK")));

        assertFalse(phone.isActive(), "heartbeat generation renewal must not suppress a kick for the same session");
        assertTrue(routeTable.conditionalRemovalAttempted,
                "the current route generation must still be removed atomically");
        sessionManager.clear();
    }

    @Test
    void retriesKickSessionClaimWhenHeartbeatRotatesGenerationDuringFirstCas() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        String phoneSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        BindingRouteTable routeTable = new BindingRouteTable(new RouteBinding(
                "u1", "node-b", PlatformID.IOS, phoneSessionId, 0, "lease-b", "generation-old"));
        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(
                sessionManager, routeTable, "node-b", "lease-b");
        RouteBinding kickSnapshot = routeTable.lookupAllBindings("u1").getFirst();
        routeTable.renewBeforeNextRemoval("generation-raced");

        handler.handle(ClusterMessage.fromCommand(
                "node-a", ClusterCommand.kickSession(kickSnapshot, "SAME_TERM_KICK")));

        assertFalse(phone.isActive(), "a concurrent heartbeat must not suppress the exact session kick");
        assertTrue(routeTable.conditionalRemovalAttempts > 1,
                "the handler must re-read the generation after a failed conditional removal");
        sessionManager.clear();
    }

    @Test
    void ignoresKickSessionCommandAfterBindingIsReplacedWithAnotherSession() {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        String oldSessionId = sessionManager.createSession(phoneRef).getSessionId();
        sessionManager.bindUser(phoneRef.connectionId(), "u1", PlatformID.IOS);
        RouteBinding stale = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, oldSessionId, 0, "lease-b", "generation-old");
        RouteBinding replacement = new RouteBinding(
                "u1", "node-b", PlatformID.IOS, "replacement-session", 0, "lease-b", "generation-new");
        BindingRouteTable routeTable = new BindingRouteTable(replacement);
        ClusterSessionCommandHandler handler = new ClusterSessionCommandHandler(
                sessionManager, routeTable, "node-b", "lease-b");

        handler.handle(ClusterMessage.fromCommand(
                "node-a", ClusterCommand.kickSession(stale, "SAME_TERM_KICK")));

        assertTrue(phone.isActive(), "a command for an old session must not kick a replacement binding");
        assertFalse(routeTable.conditionalRemovalAttempted,
                "a replacement session must not reach the conditional route removal");
        sessionManager.clear();
    }

    private static final class BindingRouteTable implements IRouteTable {
        private RouteBinding binding;
        private boolean conditionalRemovalAttempted;
        private int conditionalRemovalAttempts;
        private String generationBeforeNextRemoval;

        private BindingRouteTable(RouteBinding binding) {
            this.binding = binding;
        }

        private void renewCurrentBinding(String generation) {
            binding = new RouteBinding(binding.userId(), binding.nodeId(), binding.platformId(),
                    binding.sessionId(), binding.expireAt(), binding.nodeIncarnation(), generation);
        }

        private void renewBeforeNextRemoval(String generation) {
            generationBeforeNextRemoval = generation;
        }

        @Override public void online(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public void offline(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public boolean offlineIfCurrent(RouteBinding candidate) {
            conditionalRemovalAttempted = true;
            conditionalRemovalAttempts++;
            if (generationBeforeNextRemoval != null) {
                renewCurrentBinding(generationBeforeNextRemoval);
                generationBeforeNextRemoval = null;
            }
            if (!candidate.sameIdentity(binding)) {
                return false;
            }
            binding = null;
            return true;
        }
        @Override public RouteNode lookup(String userId) { return null; }
        @Override public List<RouteNode> lookupAll(String userId) { return List.of(); }
        @Override public List<RouteBinding> lookupAllBindings(String userId) {
            return binding != null ? List.of(binding) : List.of();
        }
        @Override public void setOnline(String userId, int platformId) {}
        @Override public void setOffline(String userId, int platformId) {}
        @Override public List<Integer> getOnlinePlatforms(String userId) { return List.of(); }
        @Override public Map<String, List<Integer>> batchGetOnlinePlatforms(List<String> userIds) { return Map.of(); }
        @Override public void renewOnline(String userId, int platformId) {}
    }
}
