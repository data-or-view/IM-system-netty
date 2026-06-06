package com.im.core.friend;

import com.im.api.ApplyHandleResult;
import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
import com.im.api.FriendApply;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.bootstrap.ws.WsPushEventEncoder;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterAwareFriendApplyNotifierTest {

    @Test
    void pushesApplyCreatedToLocalOnlineUser() throws Exception {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new WsPushEventEncoder());
        NettyConnectionRef ref = new NettyConnectionRef(channel);
        IConnectionSession session = sessionManager.createSession(ref);
        sessionManager.bindUser(ref.connectionId(), "bob");

        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("bob", List.of(new RouteBinding("bob", "node-a", session.getPlatformId(), session.getSessionId(), 0)));
        ClusterAwareFriendApplyNotifier notifier = new ClusterAwareFriendApplyNotifier(
                "node-a", sessionManager, routeTable, new RecordingClusterMessageBus());

        notifier.notifyApplyCreated("bob", apply("alice", "bob", ApplyHandleResult.PENDING));

        TextWebSocketFrame frame = awaitFrame(channel);
        assertNotNull(frame);
        assertTrue(frame.text().contains("\"op\":\"friend.apply\""));
        assertTrue(frame.text().contains("\"handleResult\":\"PENDING\""));
    }

    @Test
    void forwardsApplyHandledToRemoteNode() {
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("alice", List.of(new RouteBinding("alice", "node-b", 1, "s1", 0)));
        RecordingClusterMessageBus bus = new RecordingClusterMessageBus();
        ClusterAwareFriendApplyNotifier notifier = new ClusterAwareFriendApplyNotifier(
                "node-a", new SessionManager(), routeTable, bus);

        notifier.notifyApplyHandled("alice", apply("alice", "bob", ApplyHandleResult.AGREED));

        assertEquals("node-b", bus.targets.getFirst());
        assertEquals("friend.apply", bus.sent.getFirst().getCommand().payload().get("op"));
    }

    private static FriendApply apply(String fromUserId, String toUserId, ApplyHandleResult result) {
        FriendApply apply = new FriendApply();
        apply.setFromUserId(fromUserId);
        apply.setToUserId(toUserId);
        apply.setHandleResult(result);
        apply.setCreateTime(100);
        return apply;
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

        private TestRouteTable(String localNodeId) {
            this.localNodeId = localNodeId;
        }

        @Override public void online(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public void offline(String userId, String nodeId, int platformId, String sessionId) {}
        @Override public RouteNode lookup(String userId) { return null; }
        @Override public List<RouteNode> lookupAll(String userId) {
            return lookupAllBindings(userId).stream().map(binding -> binding.toRouteNode(localNodeId)).toList();
        }
        @Override public List<RouteBinding> lookupAllBindings(String userId) {
            return bindings.getOrDefault(userId, List.of());
        }
        @Override public void setOnline(String userId, int platformId) {}
        @Override public void setOffline(String userId, int platformId) {}
        @Override public List<Integer> getOnlinePlatforms(String userId) { return List.of(); }
        @Override public void renewOnline(String userId, int platformId) {}
    }

    private static final class RecordingClusterMessageBus implements IClusterMessageBus {
        private final CopyOnWriteArrayList<ClusterMessage> sent = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> targets = new CopyOnWriteArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void sendToNode(ClusterMessage msg, String targetNodeId) {
            sent.add(msg);
            targets.add(targetNodeId);
        }
        @Override public void broadcast(ClusterMessage msg) {}
        @Override public void subscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
        @Override public void unsubscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
    }
}
