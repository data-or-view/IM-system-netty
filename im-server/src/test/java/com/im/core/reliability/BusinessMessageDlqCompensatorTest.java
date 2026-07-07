package com.im.core.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.api.QueueMessageHandler;
import com.im.api.BusinessMessageDlqRecord;
import com.im.api.BusinessMessageDlqStore;
import com.im.core.serialization.jackson.ObjectMapperProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessMessageDlqCompensatorTest {

    private static final ObjectMapper MAPPER = ObjectMapperProvider.get();

    @Test
    void claimsDueFailureBeforeRepublishingAndMarksRepublished() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        RecordingFailureStore failureStore = new RecordingFailureStore(List.of(
                new BusinessMessageDlqRecord(1, "deliver", "m-1", payload("m-1"), 0)));
        BusinessMessageDlqCompensator compensator = new BusinessMessageDlqCompensator(
                queue, failureStore, 10, 3, 1000, 1000);

        int republished = compensator.republishDueDlqMessages();

        assertEquals(1, republished);
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
        BusinessMessageDlqCompensator compensator = new BusinessMessageDlqCompensator(
                queue, failureStore, 10, 3, 1000, 1000);

        int republished = compensator.republishDueDlqMessages();

        assertEquals(0, republished);
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
        @Override public void subscribe(String topic, QueueMessageHandler handler) {}
        @Override public void unsubscribe(String topic, QueueMessageHandler handler) {}
        @Override public boolean hasSubscribers(String topic) { return false; }
    }

    private record Published(String topic, Message message) {}

    private static final class RecordingFailureStore implements BusinessMessageDlqStore {
        private final List<BusinessMessageDlqRecord> records;
        private final List<Long> republished = new ArrayList<>();
        private int claimCalls;
        private boolean legacyFindDueCalled;

        private RecordingFailureStore(List<BusinessMessageDlqRecord> records) {
            this.records = records;
        }

        @Override
        public void recordFailure(String topic, Message message, Throwable cause) {
        }

        @Override
        public List<BusinessMessageDlqRecord> claimDueFailures(long nowMillis, int limit) {
            claimCalls++;
            return records;
        }

        @Override
        public List<BusinessMessageDlqRecord> findDueFailures(long nowMillis, int limit) {
            legacyFindDueCalled = true;
            return List.of();
        }

        @Override
        public void markRepublished(long id) {
            republished.add(id);
        }
    }
}
