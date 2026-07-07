package com.im.common.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {

    @Test
    void shouldStartAndStopInOrder() {
        var order = new ArrayList<String>();

        Lifecycle a = new Lifecycle() {
            @Override public void start() { order.add("A-start"); }
            @Override public void stop() { order.add("A-stop"); }
        };
        Lifecycle b = new Lifecycle() {
            @Override public void start() { order.add("B-start"); }
            @Override public void stop() { order.add("B-stop"); }
        };

        List<Lifecycle> components = List.of(a, b);
        assertDoesNotThrow(() -> LifecycleManager.startAll(components));
        LifecycleManager.stopAll(components);

        assertEquals(List.of("A-start", "B-start", "B-stop", "A-stop"), order);
    }

    @Test
    void shouldStopAllEvenIfOneFails() {
        Lifecycle ok = new Lifecycle() {
            @Override public void stop() { /* ok */ }
        };
        Lifecycle fail = new Lifecycle() {
            @Override public void stop() { throw new RuntimeException("stop error"); }
        };

        List<Lifecycle> components = List.of(ok, fail);
        assertDoesNotThrow(() -> LifecycleManager.stopAll(components));
    }

    @Test
    void shouldPropagateStartException() {
        Lifecycle ok = new Lifecycle() {
            @Override public void start() { /* ok */ }
        };
        Lifecycle fail = new Lifecycle() {
            @Override public void start() { throw new RuntimeException("start error"); }
        };

        List<Lifecycle> components = List.of(ok, fail);
        assertThrows(RuntimeException.class, () -> LifecycleManager.startAll(components));
    }
}
