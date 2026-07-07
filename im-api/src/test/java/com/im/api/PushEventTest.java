package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PushEventTest {

    @Test
    void envelopeKeepsOperationAndAllowsNullData() {
        Map<String, Object> envelope = new PushEvent("heartbeat", null).toEnvelope();

        assertEquals("heartbeat", envelope.get("op"));
        assertNull(envelope.get("data"));
    }
}
