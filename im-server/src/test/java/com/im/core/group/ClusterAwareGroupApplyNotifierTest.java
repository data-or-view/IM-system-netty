package com.im.core.group;

import com.im.api.ApplyHandleResult;
import com.im.api.ClusterMessage;
import com.im.api.GroupApply;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IRouteTable;
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

class ClusterAwareGroupApplyNotifierTest {

    @Test
    void pushesApplyCreatedToLocalManager() throws Exception {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new WsPushEventEncoder());
        NettyConnectionRef ref = new NettyConnectionRef(channel);
        IConnectionSession session = sessionManager.createSession(ref);
        sessionManager.bindUser(ref.connectionId(), "owner");

        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("owner", List.of(new RouteBinding("owner", "node-a", session.getPlatformId(), session.getSessionId(), 0)));
        ClusterAwareGroupApplyNotifier notifier = new ClusterAwareGroupApplyNotifier(
                "node-a", sessionManager, routeTable, new RecordingClusterMessageBus());

        notifier.notifyApplyCreated(List.of("owner"), apply("grp_1", "alice", ApplyHandleResult.PENDING));

        TextWebSocketFrame frame = awaitFrame(channel);
        assertNotNull(frame);
        assertTrue(frame.text().contains("\"op\":\"group.apply\""));
        assertTrue(frame.text().contains("\"handleResult\":\"PENDING\""));
    }

    @Test
    void forwardsApplyHandledToRemoteApplicant() {
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("alice", List.of(new RouteBinding("alice", "node-b", 1, "s1", 0)));
        RecordingClusterMessageBus bus = new RecordingClusterMessageBus();
        ClusterAwareGroupApplyNotifier notifier = new ClusterAwareGroupApplyNotifier(
                "node-a", new SessionManager(), routeTable, bus);

        notifier.notifyApplyHandled("alice", apply("grp_1", "alice", ApplyHandleResult.AGREED));

        assertEquals("node-b", bus.targets.getFirst());
        assertEquals("group.apply", bus.sent.getFirst().getCommand().payload().get("op"));
    }

    private static GroupApply apply(String groupId, String userId, ApplyHandleResult result) {
        GroupApply apply = new GroupApply();
        apply.setGroupId(groupId);
        apply.setUserId(userId);
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
        @Override public boolean sendToNode(ClusterMessage msg, String targetNodeId) {
            sent.add(msg);
            targets.add(targetNodeId);
            return true;
        }
        @Override public void broadcast(ClusterMessage msg) {}
        @Override public void subscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
        @Override public void unsubscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
    }
}
