package com.im.infrastructure.message;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageProducerTest {

    private final byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

    @Test
    void publishDelegatesToPublishBatch() {
        List<MessageEnvelope> captured = new ArrayList<>();
        MessageProducer producer = new MessageProducer() {
            @Override
            public void publishBatch(Collection<MessageEnvelope> envelopes) {
                captured.addAll(envelopes);
            }

            @Override
            public void publishDelayedBatch(Collection<MessageEnvelope> envelopes, long delayMillis) {
            }
        };

        MessageEnvelope env = MessageEnvelope.builder().channel("test").payload(payload).build();
        producer.publish(env);

        assertEquals(1, captured.size());
        assertSame(env, captured.get(0));
    }

    @Test
    void publishDelayedDelegatesToPublishDelayedBatch() {
        List<MessageEnvelope> captured = new ArrayList<>();
        MessageProducer producer = new MessageProducer() {
            @Override
            public void publishBatch(Collection<MessageEnvelope> envelopes) {
            }

            @Override
            public void publishDelayedBatch(Collection<MessageEnvelope> envelopes, long delayMillis) {
                captured.addAll(envelopes);
            }
        };

        MessageEnvelope env = MessageEnvelope.builder().channel("test").payload(payload).build();
        producer.publishDelayed(env, 5000);

        assertEquals(1, captured.size());
        assertSame(env, captured.get(0));
    }
}
