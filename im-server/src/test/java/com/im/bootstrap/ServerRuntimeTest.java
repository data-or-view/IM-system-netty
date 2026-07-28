package com.im.bootstrap;

import com.im.api.IClusterMessageBus;
import com.im.api.IMessageQueue;
import com.im.api.INodeDiscovery;
import com.im.api.NodeInformation;
import com.im.common.lifecycle.Lifecycle;
import com.im.core.call.CallStateManager;
import com.im.core.call.SingleCallSession;
import com.im.core.call.SingleCallStateStore;
import com.im.core.call.TerminalSignalIntent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerRuntimeTest {

    @Test
    void startsInfrastructureBeforeTransportAndStopsTransportBeforeInfrastructure() throws Exception {
        List<String> events = new ArrayList<>();
        RequestAdmission admission = fakeAdmission(events);
        ServerRuntime runtime = new ServerRuntime(
                fake(INodeDiscovery.class, events, "discovery"),
                new NodeInformation("node-test", "127.0.0.1", 8081, Map.of()),
                admission,
                Duration.ofSeconds(1),
                fake(IClusterMessageBus.class, events, "bus"),
                fake(IMessageQueue.class, events, "queue"),
                fake(Lifecycle.class, events, "persistence"),
                fake(Lifecycle.class, events, "delivery"),
                fake(Lifecycle.class, events, "compensator"),
                fake(Lifecycle.class, events, "transport"),
                recordingCallStateManager(events),
                () -> events.add("connection.shutdown"),
                () -> events.add("pending.shutdown"),
                () -> events.add("session.clear"),
                null,
                null);

        runtime.start();
        runtime.stop();

        // This order is a production invariant: requests enter only after routing and
        // persistence are ready, and transport closes before shared dependencies disappear.
        assertEquals(List.of(
                "discovery.start",
                "discovery.register",
                "bus.start",
                "queue.start",
                "persistence.start",
                "delivery.start",
                "compensator.start",
                "transport.start",
                "admission.open",
                "admission.closeAndDrain",
                "transport.stop",
                "call.shutdown",
                "compensator.stop",
                "delivery.stop",
                "persistence.stop",
                "queue.stop",
                "bus.stop",
                "discovery.unregister",
                "discovery.stop",
                "connection.shutdown",
                "pending.shutdown",
                "session.clear"), events);
    }

    private static CallStateManager recordingCallStateManager(List<String> events) {
        return new CallStateManager(null, new EmptyCallStateStore(), 30, 60_000, 1) {
            @Override
            public void shutdown() {
                super.shutdown();
                events.add("call.shutdown");
            }
        };
    }

    private static final class EmptyCallStateStore implements SingleCallStateStore {
        @Override public SingleCallSession getByRoom(String roomId) { return null; }
        @Override public SingleCallSession getActiveByUser(String userId) { return null; }
        @Override public SingleCallSession createIfUsersIdle(SingleCallSession session) { return null; }
        @Override public TerminalSignalIntent getPendingTerminalSignal(String roomId) { return null; }
        @Override public boolean transitionTerminalSignal(TerminalSignalIntent intent) { return false; }
        @Override public boolean acknowledgeTerminalSignal(TerminalSignalIntent intent) { return false; }
        @Override public SingleCallSession accept(String roomId) { return null; }
        @Override public SingleCallSession timeoutIfRinging(String roomId) { return null; }
        @Override public SingleCallSession end(String roomId) { return null; }
    }

    private static RequestAdmission fakeAdmission(List<String> events) {
        return new RequestAdmission() {
            @Override
            public RequestScope enter() {
                return () -> {
                };
            }

            @Override
            public void open() {
                events.add("admission.open");
            }

            @Override
            public void closeAndDrain(Duration timeout) {
                events.add("admission.closeAndDrain");
            }

            @Override
            public boolean isOpen() {
                return true;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, List<String> events, String name) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "start", "stop", "register", "unregister", "shutdown", "clear" ->
                                events.add(name + "." + method.getName());
                        default -> {
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0F;
        if (returnType == Double.TYPE) return 0D;
        if (returnType == Character.TYPE) return '\0';
        return null;
    }
}
