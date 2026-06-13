package com.im.core.reliability;

import com.im.api.IMessageQueue;
import com.im.api.Message;
import com.im.common.retry.RetryConfig;
import com.im.common.retry.RetryExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReliableMessageHandlerTest {

    @Test
    void recordsDeadLetterWhenConsumerProcessingFails() {
        RecordingFailureStore failureStore = new RecordingFailureStore();
        ReliableMessageHandler handler = new ReliableMessageHandler(
                "persist",
                msg -> { throw new IllegalStateException("db down"); },
                new DirectRetryExecutor(),
                SendMessageIdempotency.none(),
                failureStore);

        handler.onMessage(message("m-1"));

        assertEquals(1, failureStore.recorded.get());
        assertEquals("persist", failureStore.topic);
        assertEquals("m-1", failureStore.message.getMessageId());
    }

    @Test
    void throwsWhenDeadLetterRecordAlsoFails() {
        ReliableMessageHandler handler = new ReliableMessageHandler(
                "deliver",
                msg -> { throw new IllegalStateException("push failed"); },
                new DirectRetryExecutor(),
                SendMessageIdempotency.none(),
                (topic, message, cause) -> { throw new IllegalStateException("db down"); });

        assertThrows(RuntimeException.class, () -> handler.onMessage(message("m-2")));
    }

    private static Message message(String id) {
        Message message = new Message();
        message.setMessageId(id);
        message.setConversationId("single_alice_bob");
        return message;
    }

    private static final class DirectRetryExecutor implements RetryExecutor {
        @Override
        public <T> T execute(RetryConfig config, Callable<T> callable) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class RecordingFailureStore implements SendMessageFailureStore {
        private final AtomicInteger recorded = new AtomicInteger();
        private String topic;
        private Message message;

        @Override
        public void recordFailure(String topic, Message message, Throwable cause) {
            this.topic = topic;
            this.message = message;
            recorded.incrementAndGet();
        }
    }
}
