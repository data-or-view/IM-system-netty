package com.im.core.call;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallStateManagerTimeoutTest {

    @Test
    void liveNodeClaimsAndPublishesDeadlineCreatedByStoppedNodeExactlyOnce() {
        InMemoryDeadlineStore store = new InMemoryDeadlineStore();
        store.session = new SingleCallSession("room-1", "caller", "callee", "voice",
                SingleCallSession.STATUS_RINGING, "ws://sfu", 1L, 0L);
        RecordingQueue queue = new RecordingQueue();
        CallStateManager creator = new CallStateManager(queue, store, 30);
        CallStateManager recoveringNode = new CallStateManager(queue, store, 30);

        try {
            creator.shutdown();

            recoveringNode.scanExpiredCalls();
            recoveringNode.scanExpiredCalls();

            assertEquals(2, queue.published.size());
            assertEquals(1, store.claimCalls);
            assertEquals(UUID.nameUUIDFromBytes("single-call-timeout:room-1:caller"
                    .getBytes(StandardCharsets.UTF_8)).toString(), queue.published.get(0).getMessageId());
            assertEquals(UUID.nameUUIDFromBytes("single-call-timeout:room-1:callee"
                    .getBytes(StandardCharsets.UTF_8)).toString(), queue.published.get(1).getMessageId());
        } finally {
            creator.shutdown();
            recoveringNode.shutdown();
        }
    }

    private static final class InMemoryDeadlineStore implements SingleCallStateStore {
        private SingleCallSession session;
        private int claimCalls;

        @Override public SingleCallSession getByRoom(String roomId) { return session; }
        @Override public SingleCallSession getActiveByUser(String userId) { return session; }
        @Override public SingleCallSession createIfUsersIdle(SingleCallSession candidate) { session = candidate; return session; }
        @Override public SingleCallSession accept(String roomId) { return session != null ? session.accept(System.currentTimeMillis()) : null; }
        @Override public SingleCallSession timeoutIfRinging(String roomId) { return null; }
        @Override public SingleCallSession end(String roomId) { return session != null ? session.end() : null; }

        @Override
        public List<SingleCallSession> claimExpiredRinging(long nowEpochMillis, int limit) {
            if (session == null || !SingleCallSession.STATUS_RINGING.equals(session.status())) return List.of();
            claimCalls++;
            SingleCallSession claimed = session.end();
            session = null;
            return List.of(claimed);
        }
    }

    private static final class RecordingQueue implements IMessageQueue {
        private final List<Message> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) { published.add(msg); }
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }
}
