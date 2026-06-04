package com.im.common.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IMExecutorsTest {

    @Test
    void virtualExecutorUsesNamedVirtualThreads() throws Exception {
        ExecutorService executor = IMExecutors.newVirtualThreadExecutor("im-test");
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Thread> threadRef = new AtomicReference<>();

            executor.execute(() -> {
                threadRef.set(Thread.currentThread());
                done.countDown();
            });

            assertTrue(done.await(3, TimeUnit.SECONDS));
            Thread thread = threadRef.get();
            assertTrue(thread.isVirtual());
            assertTrue(thread.getName().startsWith("im-test-"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void scheduledExecutorUsesNamedDaemonPlatformThreads() throws Exception {
        ScheduledExecutorService executor = IMExecutors.newScheduledExecutor("im-schedule-test", 1);
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Thread> threadRef = new AtomicReference<>();

            executor.schedule(() -> {
                threadRef.set(Thread.currentThread());
                done.countDown();
            }, 1, TimeUnit.MILLISECONDS);

            assertTrue(done.await(3, TimeUnit.SECONDS));
            Thread thread = threadRef.get();
            assertFalse(thread.isVirtual());
            assertTrue(thread.isDaemon());
            assertTrue(thread.getName().startsWith("im-schedule-test-scheduler-"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executorsRejectBlankNamePrefix() {
        assertThrows(IllegalArgumentException.class, () -> IMExecutors.newVirtualThreadExecutor(" "));
        assertThrows(IllegalArgumentException.class, () -> IMExecutors.newScheduledExecutor("", 1));
    }

    @Test
    void scheduledExecutorRejectsNonPositiveCoreSize() {
        assertThrows(IllegalArgumentException.class, () -> IMExecutors.newScheduledExecutor("im-test", 0));
        assertThrows(IllegalArgumentException.class, () -> IMExecutors.newScheduledExecutor("im-test", -1));
    }
}
