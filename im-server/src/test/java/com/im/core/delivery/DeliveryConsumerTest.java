package com.im.core.delivery;

import com.im.api.ClusterMessage;
import com.im.api.IClusterMessageBus;
import com.im.api.IConnectionSession;
import com.im.api.IMessageQueue;
import com.im.api.IRouteTable;
import com.im.api.Message;
import com.im.api.MessageQueueTopics;
import com.im.api.PlatformID;
import com.im.api.RouteBinding;
import com.im.api.RouteNode;
import com.im.core.session.NettyConnectionRef;
import com.im.core.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryConsumerTest {

    @Test
    void deliversLocalRouteBindingOnlyToMatchingSession() throws Exception {
        SessionManager sessionManager = new SessionManager();
        EmbeddedChannel phone = new EmbeddedChannel();
        EmbeddedChannel desktop = new EmbeddedChannel();
        NettyConnectionRef phoneRef = new NettyConnectionRef(phone);
        NettyConnectionRef desktopRef = new NettyConnectionRef(desktop);

        IConnectionSession phoneSession = sessionManager.createSession(phoneRef);
        sessionManager.bindUser(phoneRef.connectionId(), "u2", PlatformID.IOS);
        sessionManager.createSession(desktopRef);
        sessionManager.bindUser(desktopRef.connectionId(), "u2", PlatformID.WINDOWS);

        TestMessageQueue queue = new TestMessageQueue();
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("u2", List.of(new RouteBinding(
                "u2", "node-a", PlatformID.IOS, phoneSession.getSessionId(), 0)));
        DeliveryConsumer consumer = new DeliveryConsumer(
                queue, sessionManager, routeTable, new NoopClusterMessageBus(), "node-a");

        try {
            consumer.start();
            queue.handler(MessageQueueTopics.DELIVER).onMessage(
                    Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1));

            assertNotNull(awaitOutbound(phone), "matching session should receive the message");
            assertNull(awaitOutbound(desktop), "non-matching session must not receive duplicate delivery");
        } finally {
            consumer.stop();
            sessionManager.clear();
        }
    }

    @Test
    void removesStaleLocalRouteWhenMatchingSessionIsMissing() {
        TestMessageQueue queue = new TestMessageQueue();
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("u2", List.of(new RouteBinding(
                "u2", "node-a", PlatformID.IOS, "missing-session", 0)));
        DeliveryConsumer consumer = new DeliveryConsumer(
                queue, new SessionManager(), routeTable, new NoopClusterMessageBus(), "node-a");

        try {
            consumer.start();
            queue.handler(MessageQueueTopics.DELIVER).onMessage(
                    Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1));

            assertEquals(List.of("u2|node-a|1|missing-session"), routeTable.offlineCalls);
        } finally {
            consumer.stop();
        }
    }

    @Test
    void skipsExpiredRemoteRouteBinding() throws Exception {
        TestMessageQueue queue = new TestMessageQueue();
        TestRouteTable routeTable = new TestRouteTable("node-a");
        RecordingClusterMessageBus bus = new RecordingClusterMessageBus();
        routeTable.bindings.put("u2", List.of(new RouteBinding(
                "u2", "node-b", PlatformID.IOS, "s1", System.currentTimeMillis() - 1)));
        DeliveryConsumer consumer = new DeliveryConsumer(queue, new SessionManager(), routeTable, bus, "node-a");

        try {
            consumer.start();
            queue.handler(MessageQueueTopics.DELIVER).onMessage(
                    Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1));

            assertNull(bus.awaitSent(), "expired route binding must not be forwarded");
        } finally {
            consumer.stop();
        }
    }

    @Test
    void remoteForwardFailurePropagatesToReliableDeliveryChain() {
        TestMessageQueue queue = new TestMessageQueue();
        TestRouteTable routeTable = new TestRouteTable("node-a");
        routeTable.bindings.put("u2", List.of(new RouteBinding(
                "u2", "node-b", PlatformID.IOS, "s1", 0)));
        DeliveryConsumer consumer = new DeliveryConsumer(
                queue, new SessionManager(), routeTable, new FailingClusterMessageBus(), "node-a");

        try {
            consumer.start();
            Message message = Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1);

            assertThrows(IllegalStateException.class,
                    () -> queue.handler(MessageQueueTopics.DELIVER).onMessage(message));
        } finally {
            consumer.stop();
        }
    }

    @Test
    void forwardsEachRemoteBindingWithTargetSessionMetadata() throws Exception {
        TestMessageQueue queue = new TestMessageQueue();
        TestRouteTable routeTable = new TestRouteTable("node-a");
        RecordingClusterMessageBus bus = new RecordingClusterMessageBus();
        routeTable.bindings.put("u2", List.of(
                new RouteBinding("u2", "node-b", PlatformID.IOS, "ios-session", 0),
                new RouteBinding("u2", "node-b", PlatformID.WINDOWS, "pc-session", 0)));
        DeliveryConsumer consumer = new DeliveryConsumer(queue, new SessionManager(), routeTable, bus, "node-a");

        try {
            consumer.start();
            queue.handler(MessageQueueTopics.DELIVER).onMessage(
                    Message.createSingle("u1", "u2", "c1", 101, "{\"text\":\"hi\"}", 1));

            assertEquals(2, bus.awaitSentCount(2), "same-node bindings must not be collapsed");
            assertTrue(bus.sent.stream().anyMatch(message ->
                    message.getTargetPlatformId() == PlatformID.IOS
                            && "ios-session".equals(message.getTargetSessionId())));
            assertTrue(bus.sent.stream().anyMatch(message ->
                    message.getTargetPlatformId() == PlatformID.WINDOWS
                            && "pc-session".equals(message.getTargetSessionId())));
        } finally {
            consumer.stop();
        }
    }

    private static Object awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Object outbound;
        while ((outbound = channel.readOutbound()) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return outbound;
    }

    private static final class TestMessageQueue implements IMessageQueue {
        private final Map<String, IMessageQueue.MessageHandler> handlers = new ConcurrentHashMap<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void publish(String topic, Message msg) {
        }

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            handlers.put(topic, handler);
        }

        @Override
        public void unsubscribe(String topic, MessageHandler handler) {
            handlers.remove(topic, handler);
        }

        @Override
        public boolean hasSubscribers(String topic) {
            return handlers.containsKey(topic);
        }

        MessageHandler handler(String topic) {
            return handlers.get(topic);
        }
    }

    private static final class TestRouteTable implements IRouteTable {
        private final String localNodeId;
        private final Map<String, List<RouteBinding>> bindings = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<String> offlineCalls = new CopyOnWriteArrayList<>();

        private TestRouteTable(String localNodeId) {
            this.localNodeId = localNodeId;
        }

        @Override
        public void online(String userId, String nodeId, int platformId, String sessionId) {
        }

        @Override
        public void offline(String userId, String nodeId, int platformId, String sessionId) {
            offlineCalls.add(userId + "|" + nodeId + "|" + platformId + "|" + sessionId);
        }

        @Override
        public RouteNode lookup(String userId) {
            return null;
        }

        @Override
        public List<RouteNode> lookupAll(String userId) {
            return lookupAllBindings(userId).stream()
                    .map(binding -> binding.toRouteNode(localNodeId))
                    .toList();
        }

        @Override
        public List<RouteBinding> lookupAllBindings(String userId) {
            return bindings.getOrDefault(userId, List.of());
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

    private static final class NoopClusterMessageBus implements IClusterMessageBus {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean sendToNode(ClusterMessage message, String targetNodeId) {
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

    private static final class RecordingClusterMessageBus implements IClusterMessageBus {
        private final CopyOnWriteArrayList<ClusterMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean sendToNode(ClusterMessage message, String targetNodeId) {
            sent.add(message);
            return true;
        }

        @Override
        public void broadcast(ClusterMessage msg) {
            sent.add(msg);
        }

        @Override
        public void subscribe(String topic, com.im.api.ClusterMessageHandler handler) {
        }

        @Override
        public void unsubscribe(String topic, com.im.api.ClusterMessageHandler handler) {
        }

        ClusterMessage awaitSent() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sent.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            return sent.isEmpty() ? null : sent.getFirst();
        }

        int awaitSentCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sent.size() < expected && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            return sent.size();
        }
    }

    private static final class FailingClusterMessageBus implements IClusterMessageBus {
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean sendToNode(ClusterMessage message, String targetNodeId) {
            return false;
        }
        @Override public void broadcast(ClusterMessage msg) {}
        @Override public void subscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
        @Override public void unsubscribe(String topic, com.im.api.ClusterMessageHandler handler) {}
    }
}
