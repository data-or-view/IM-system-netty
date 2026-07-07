package com.im.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PushEventTest {

    @Test
    void envelopeUsesUnifiedSuccessShape() {
        PushEvent event = new PushEvent("msg_revoke", Map.of("seq", 7));

        Map<String, Object> envelope = event.toEnvelope();

        assertEquals("msg_revoke", envelope.get("op"));
        assertEquals(0, envelope.get("code"));
        assertEquals("ok", envelope.get("msg"));
        assertEquals(Map.of("seq", 7), envelope.get("data"));
    }
}
