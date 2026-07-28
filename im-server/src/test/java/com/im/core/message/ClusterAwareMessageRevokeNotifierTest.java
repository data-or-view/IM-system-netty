package com.im.core.message;

import com.im.api.ClusterCommand;
import com.im.api.ClusterCommandType;
import com.im.api.ClusterMessage;
import com.im.api.ClusterMessageHandler;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.PlatformID;
import com.im.api.ProtocolFields;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.bootstrap.ws.WsPushEventEncoder;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import com.im.core.usecase.RevokeResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterAwareMessageRevokeNotifierTest {

    @Test
    void pushesRevokeOnlyToMatchingLocalSession() throws Exception {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel(new WsPushEventEncoder());
        EmbeddedChannel desktop = new EmbeddedChannel(new WsPushEventEncoder());
        IConnectionSession phoneSession = sessionManager.createSession(new NettyConnectionRef(phone));
        IConnectionSession desktopSession = sessionManager.createSession(new NettyConnectionRef(desktop));
        sessionManager.bindUser(phoneSession.getConnection().connectionId(), "bob", PlatformID.IOS);
        sessionManager.bindUser(desktopSession.getConnection().connectionId(), "bob", PlatformID.WINDOWS);

        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("bob", List.of(new RouteBinding(
                "bob", "node-a", PlatformID.IOS, phoneSession.getSessionId(), 0)));
        ClusterAwareMessageRevokeNotifier notifier = new ClusterAwareMessageRevokeNotifier(
                "node-a", sessionManager, routeTable, new RecordingClusterMessageBus());

        notifier.notify(result("alice", "bob"));

        TextWebSocketFrame frame = awaitFrame(phone);
        assertNotNull(frame);
        assertTrue(frame.text().contains("\"op\":\"msg_revoke\""));
        assertTrue(frame.text().contains("\"conversationId\":\"single_alice_bob\""));
        assertTrue(frame.text().contains("\"seq\":7"));
        assertNull(desktop.readOutbound(), "non-target local session must not receive duplicate revoke push");
    }

    @Test
    void forwardsRevokeToRemoteNodeWithTargetBinding() {
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("bob", List.of(new RouteBinding(
                "bob", "node-b", PlatformID.IOS, "s1", 0, "lease-b", "generation-b")));
        RecordingClusterMessageBus bus = new RecordingClusterMessageBus();
        ClusterAwareMessageRevokeNotifier notifier = new ClusterAwareMessageRevokeNotifier(
                "node-a", new SessionManager(), routeTable, bus);

        notifier.notify(result("alice", "bob"));

        assertEquals("node-b", bus.targets.getFirst());
        ClusterCommand command = bus.sent.getFirst().getCommand();
        assertEquals(ClusterCommandType.PUSH_EVENT, command.type());
        assertEquals("msg_revoke", command.payload().get(ProtocolFields.OP));
        assertEquals(PlatformID.IOS, command.platformId());
        assertEquals("s1", command.sessionId());
        assertEquals("lease-b", command.nodeIncarnation());
        assertEquals("generation-b", command.generation());
    }

    @Test
    void staleLocalSnapshotCannotRemoveRenewedBindingOrOnlinePlatform() {
        TestRouteTable routeTable = new TestRouteTable("node-a");
        RouteBinding observed = new RouteBinding(
                "bob", "node-a", PlatformID.IOS, "s1", 0, "lease-a", "generation-1");
        RouteBinding renewed = new RouteBinding(
                "bob", "node-a", PlatformID.IOS, "s1", 0, "lease-a", "generation-2");
        routeTable.bindings.put("bob", List.of(observed));
        routeTable.onlinePlatforms.add(PlatformID.IOS);
        routeTable.afterLookup = () -> routeTable.bindings.put("bob", List.of(renewed));
        ClusterAwareMessageRevokeNotifier notifier = new ClusterAwareMessageRevokeNotifier(
                "node-a", new SessionManager(), routeTable, new RecordingClusterMessageBus());

        notifier.notify(result("alice", "bob"));

        assertEquals(List.of(renewed), routeTable.lookupAllBindings("bob"));
        assertTrue(routeTable.getOnlinePlatforms("bob").contains(PlatformID.IOS));
    }

    @Test
    void handlesClusterPushOnlyForTargetSession() throws Exception {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel(new WsPushEventEncoder());
        EmbeddedChannel desktop = new EmbeddedChannel(new WsPushEventEncoder());
        IConnectionSession phoneSession = sessionManager.createSession(new NettyConnectionRef(phone));
        IConnectionSession desktopSession = sessionManager.createSession(new NettyConnectionRef(desktop));
        sessionManager.bindUser(phoneSession.getConnection().connectionId(), "bob", PlatformID.IOS);
        sessionManager.bindUser(desktopSession.getConnection().connectionId(), "bob", PlatformID.WINDOWS);

        ClusterAwareMessageRevokeNotifier notifier = new ClusterAwareMessageRevokeNotifier(
                "node-b", sessionManager, new TestRouteTable("node-b"), new RecordingClusterMessageBus());

        notifier.handleClusterPush(ClusterMessage.fromCommand("node-a", new ClusterCommand(
                ClusterCommandType.PUSH_EVENT,
                "bob",
                PlatformID.WINDOWS,
                desktopSession.getSessionId(),
                "PUSH_EVENT",
                revokePayload())));

        assertNull(phone.readOutbound(), "non-target remote session must not receive duplicate revoke push");
        TextWebSocketFrame frame = awaitFrame(desktop);
        assertNotNull(frame);
        assertTrue(frame.text().contains("\"op\":\"msg_revoke\""));
        assertTrue(frame.text().contains("\"revokerId\":\"alice\""));
    }

    @Test
    void staleRemoteCommandCannotRemoveRenewedBindingOrOnlinePlatform() {
        TestRouteTable routeTable = new TestRouteTable("node-b");
        RouteBinding renewed = new RouteBinding(
                "bob", "node-b", PlatformID.IOS, "s1", 0, "lease-b", "generation-2");
        routeTable.bindings.put("bob", List.of(renewed));
        routeTable.onlinePlatforms.add(PlatformID.IOS);
        ClusterAwareMessageRevokeNotifier notifier = new ClusterAwareMessageRevokeNotifier(
                "node-b", new SessionManager(), routeTable, new RecordingClusterMessageBus());

        notifier.handleClusterPush(ClusterMessage.fromCommand("node-a", new ClusterCommand(
                ClusterCommandType.PUSH_EVENT,
                "bob",
                PlatformID.IOS,
                "s1",
                "lease-b",
                "generation-1",
                "PUSH_EVENT",
                revokePayload())));

        assertEquals(List.of(renewed), routeTable.lookupAllBindings("bob"));
        assertTrue(routeTable.getOnlinePlatforms("bob").contains(PlatformID.IOS));
    }

    private static RevokeResult result(String revokerId, String targetUserId) {
        return new RevokeResult("single_alice_bob", 7, revokerId, Set.of(targetUserId));
    }

    private static Map<String, Object> revokePayload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ProtocolFields.CONVERSATION_ID, "single_alice_bob");
        data.put(ProtocolFields.SEQ, 7);
        data.put(ProtocolFields.REVOKER_ID, "alice");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(ProtocolFields.OP, ProtocolFields.OP_MESSAGE_REVOKED);
        payload.put(ProtocolFields.DATA, data);
        return payload;
    }

    private static TextWebSocketFrame awaitFrame(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Object outbound;
        while ((outbound = channel.readOutbound()) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return (TextWebSocketFrame) outbound;
    }

    private static final class TestRouteTable implements IRouteTable {
        private final String localNodeId;
        private final Map<String, List<RouteBinding>> bindings = new ConcurrentHashMap<>();
        private final Set<Integer> onlinePlatforms = ConcurrentHashMap.newKeySet();
        private Runnable afterLookup;

        private TestRouteTable(String localNodeId) {
            this.localNodeId = localNodeId;
        }

        @Override public void online(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public void offline(String userId, String nodeId, int platformId, String sessionId) {
            remove(userId, binding -> nodeId.equals(binding.nodeId())
                    && platformId == binding.platformId()
                    && sessionId.equals(binding.sessionId()));
        }
        @Override public boolean offlineIfCurrent(RouteBinding candidate) {
            return remove(candidate.userId(), candidate::sameIdentity);
        }
        @Override public RouteNode lookup(String userId) { return null; }
        @Override public List<RouteNode> lookupAll(String userId) {
            return lookupAllBindings(userId).stream().map(binding -> binding.toRouteNode(localNodeId)).toList();
        }
        @Override public List<RouteBinding> lookupAllBindings(String userId) {
            List<RouteBinding> snapshot = bindings.getOrDefault(userId, List.of());
            if (afterLookup != null) {
                Runnable hook = afterLookup;
                afterLookup = null;
                hook.run();
            }
            return snapshot;
        }
        @Override public void setOnline(String userId, int platformId) {}
        @Override public void setOffline(String userId, int platformId) {}
        @Override public List<Integer> getOnlinePlatforms(String userId) { return List.copyOf(onlinePlatforms); }
        @Override public void renewOnline(String userId, int platformId) {}

        private boolean remove(String userId, java.util.function.Predicate<RouteBinding> predicate) {
            List<RouteBinding> current = bindings.getOrDefault(userId, List.of());
            List<RouteBinding> remaining = current.stream().filter(predicate.negate()).toList();
            if (remaining.size() == current.size()) return false;
            bindings.put(userId, remaining);
            for (RouteBinding removed : current) {
                if (predicate.test(removed) && remaining.stream()
                        .noneMatch(binding -> binding.platformId() == removed.platformId())) {
                    onlinePlatforms.remove(removed.platformId());
                }
            }
            return true;
        }
    }

    private static final class RecordingClusterMessageBus implements IClusterMessageBus {
        private final CopyOnWriteArrayList<ClusterMessage> sent = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> targets = new CopyOnWriteArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean sendToNode(ClusterMessage msg, String targetNodeId) {
            sent.add(msg);
            targets.add(targetNodeId);
            return true;
        }
        @Override public void broadcast(ClusterMessage msg) {}
        @Override public void subscribe(String topic, ClusterMessageHandler handler) {}
        @Override public void unsubscribe(String topic, ClusterMessageHandler handler) {}
    }
}
