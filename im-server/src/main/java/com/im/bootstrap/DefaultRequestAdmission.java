package com.im.bootstrap;

import com.im.common.enums.ImErrorCode;
import com.im.common.exception.InfrastructureException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class DefaultRequestAdmission implements RequestAdmission {

    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final Object drainMonitor = new Object();

    @Override
    public RequestScope enter() {
        if (!open.get()) {
            throw unavailable();
        }

        activeRequests.incrementAndGet();
        if (!open.get()) {
            leave();
            throw unavailable();
        }
        return new Scope();
    }

    @Override
    public void open() {
        open.set(true);
    }

    @Override
    public void closeAndDrain(Duration timeout) {
        open.set(false);
        long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
        synchronized (drainMonitor) {
            // Shutdown must reject new requests immediately, but existing handlers may
            // still be persisting or routing messages that should finish before queues close.
            while (activeRequests.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    long millis = Math.max(1L, remaining / 1_000_000L);
                    drainMonitor.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    private InfrastructureException unavailable() {
        return new InfrastructureException(ImErrorCode.MQ_UNAVAILABLE, "server is not accepting requests");
    }

    private void leave() {
        if (activeRequests.decrementAndGet() == 0) {
            synchronized (drainMonitor) {
                drainMonitor.notifyAll();
            }
        }
    }

    private final class Scope implements RequestScope {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                leave();
            }
        }
    }
}
