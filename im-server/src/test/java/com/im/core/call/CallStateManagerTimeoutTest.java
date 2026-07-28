package com.im.core.call;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.im.api.IMessageQueue;
import com.im.api.BusinessMessageDlqStore;
import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.LoggerFactory;

class CallStateManagerTimeoutTest {

    @Test
    void liveNodeClaimsAndPublishesDeadlineCreatedByStoppedNodeExactlyOnce() {
        InMemoryDeadlineStore store = new InMemoryDeadlineStore();
        store.session = new SingleCallSession("room-1", "caller", "callee", "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", 1L, 0L);
        RecordingQueue queue = new RecordingQueue();
        CallStateManager creator = new CallStateManager(queue, store, 30);
        CallStateManager recoveringNode = null;

        try {
            creator.shutdown();
            recoveringNode = new CallStateManager(queue, store, 30);

            recoveringNode.scanExpiredCalls();
            recoveringNode.scanExpiredCalls();

            assertEquals(2, queue.published.size());
            assertEquals(1, store.claimCalls);
            assertEquals(SingleCallSession.STATUS_TIMED_OUT, store.terminalSnapshot.status());
            assertEquals(UUID.nameUUIDFromBytes("single-call-timeout:room-1:caller"
                    .getBytes(StandardCharsets.UTF_8)).toString(), queue.published.get(0).getMessageId());
            assertEquals(UUID.nameUUIDFromBytes("single-call-timeout:room-1:callee"
                    .getBytes(StandardCharsets.UTF_8)).toString(), queue.published.get(1).getMessageId());
        } finally {
            creator.shutdown();
            if (recoveringNode != null) recoveringNode.shutdown();
        }
    }

    @Test
    void recordsEachTimeoutRecipientForDurableRetryWhenDeliveryPublishFails() {
        InMemoryDeadlineStore store = new InMemoryDeadlineStore();
        store.session = new SingleCallSession("room-2", "caller", "callee", "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", 1L, 0L);
        RecordingFailureStore failures = new RecordingFailureStore();
        CallStateManager manager = new CallStateManager(new FailingQueue(), store, 30,
                60_000L, 100, new NoRetryExecutor(), failures);
        Logger logger = (Logger) LoggerFactory.getLogger(CallStateManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            manager.scanExpiredCalls();

            assertEquals(List.of(
                    UUID.nameUUIDFromBytes("single-call-timeout:room-2:caller".getBytes(StandardCharsets.UTF_8)).toString(),
                    UUID.nameUUIDFromBytes("single-call-timeout:room-2:callee".getBytes(StandardCharsets.UTF_8)).toString()),
                    failures.messages.stream().map(Message::getMessageId).toList());
            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .toList();
            assertEquals(2, warnings.size());
            assertTrue(warnings.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            manager.shutdown();
        }
    }

    private static final class InMemoryDeadlineStore implements SingleCallStateStore {
        private SingleCallSession session;
        private SingleCallSession terminalSnapshot;
        private int claimCalls;

        @Override public SingleCallSession getByRoom(String roomId) { return session; }
        @Override public SingleCallSession getActiveByUser(String userId) { return session; }
        @Override public SingleCallSession createIfUsersIdle(SingleCallSession candidate) { session = candidate; return session; }
        @Override public TerminalSignalIntent getPendingTerminalSignal(String roomId) { return null; }
        @Override public boolean transitionTerminalSignal(TerminalSignalIntent intent) { return false; }
        @Override public boolean acknowledgeTerminalSignal(TerminalSignalIntent intent) { return false; }
        @Override public SingleCallSession accept(String roomId) { return session != null ? session.accept(System.currentTimeMillis()) : null; }
        @Override public SingleCallSession timeoutIfRinging(String roomId) { return null; }
        @Override public SingleCallSession end(String roomId) { return session != null ? session.end() : null; }

        @Override
        public List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit) {
            if (session == null || !SingleCallSession.STATUS_RINGING.equals(session.status())) return List.of();
            claimCalls++;
            SingleCallSession claimed = session.timedOut();
            terminalSnapshot = claimed;
            session = null;
            return List.of(claimed);
        }
    }

    private static class RecordingQueue implements IMessageQueue {
        private final List<Message> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) { published.add(msg); }
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private static final class FailingQueue extends RecordingQueue {
        @Override public void publish(String topic, Message msg) { throw new IllegalStateException("queue unavailable"); }
    }

    private static final class RecordingFailureStore implements BusinessMessageDlqStore {
        private final List<Message> messages = new ArrayList<>();

        @Override public void recordFailure(String topic, Message message, Throwable cause) {
            messages.add(message);
        }
    }

    private static final class NoRetryExecutor implements RetryExecutor {
        @Override
        public <T> T execute(RetryConfig config, java.util.concurrent.Callable<T> callable) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
