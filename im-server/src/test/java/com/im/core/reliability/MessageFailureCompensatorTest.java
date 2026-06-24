package com.im.core.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.MessageSendFailureRecord;
import com.im.api.SendMessageFailureStore;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageFailureCompensatorTest {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Test
    void claimsDueFailureBeforeRepublishingAndMarksRepublished() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        RecordingFailureStore failureStore = new RecordingFailureStore(List.of(
                new MessageSendFailureRecord(1, "deliver", "m-1", payload("m-1"), 0)));
        MessageFailureCompensator compensator = new MessageFailureCompensator(
                queue, failureStore, 10, 3, 1000, 1000);

        int replayed = compensator.replayDueFailures();

        assertEquals(1, replayed);
        assertEquals(1, failureStore.claimCalls);
        assertFalse(failureStore.legacyFindDueCalled, "compensator must claim instead of scanning legacy due records");
        assertEquals("deliver", queue.published.get(0).topic);
        assertEquals("m-1", queue.published.get(0).message.getMessageId());
        assertEquals(List.of(1L), failureStore.republished);
    }

    @Test
    void doesNotRepublishWhenAnotherNodeAlreadyClaimedFailure() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        RecordingFailureStore failureStore = new RecordingFailureStore(List.of());
        MessageFailureCompensator compensator = new MessageFailureCompensator(
                queue, failureStore, 10, 3, 1000, 1000);

        int replayed = compensator.replayDueFailures();

        assertEquals(0, replayed);
        assertEquals(1, failureStore.claimCalls);
        assertTrue(queue.published.isEmpty());
    }

    private static String payload(String messageId) throws Exception {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setConversationId("single_alice_bob");
        return MAPPER.writeValueAsString(message.toJsonMap());
    }

    private static final class RecordingQueue implements IMessageQueue {
        private final List<Published> published = new ArrayList<>();

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void publish(String topic, Message msg) { published.add(new Published(topic, msg)); }
        @Override public void subscribe(String topic, MessageHandler handler) {}
        @Override public void unsubscribe(String topic, MessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private record Published(String topic, Message message) {}

    private static final class RecordingFailureStore implements SendMessageFailureStore {
        private final List<MessageSendFailureRecord> records;
        private final List<Long> republished = new ArrayList<>();
        private int claimCalls;
        private boolean legacyFindDueCalled;

        private RecordingFailureStore(List<MessageSendFailureRecord> records) {
            this.records = records;
        }

        @Override
        public void recordFailure(String topic, Message message, Throwable cause) {
        }

        @Override
        public List<MessageSendFailureRecord> claimDueFailures(long nowMillis, int limit) {
            claimCalls++;
            return records;
        }

        @Override
        public List<MessageSendFailureRecord> findDueFailures(long nowMillis, int limit) {
            legacyFindDueCalled = true;
            return List.of();
        }

        @Override
        public void markRepublished(long id) {
            republished.add(id);
        }
    }
}
