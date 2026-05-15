package com.im.infrastructure.message;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageEnvelopeTest {

    @Test
    void buildWithRequiredFields() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope env = MessageEnvelope.builder()
                .channel("persist")
                .payload(payload)
                .build();

        assertEquals("persist", env.getChannel());
        assertSame(payload, env.getPayload());
        assertEquals("application/json", env.getContentType());
        assertNotNull(env.getCreatedAt());
        assertNull(env.getDeliverAt());
        assertNull(env.getEventType());
        assertNull(env.getMessageKey());
        assertNull(env.getBusinessKey());
        assertTrue(env.getHeaders().isEmpty());
    }

    @Test
    void buildWithAllFields() {
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        Instant later = now.plusSeconds(60);

        MessageEnvelope env = MessageEnvelope.builder()
                .channel("deliver")
                .eventType("SINGLE_CHAT")
                .messageKey("msg_001")
                .businessKey("conv_001")
                .payload(payload)
                .contentType("application/protobuf")
                .createdAt(now)
                .deliverAt(later)
                .header("traceId", "abc123")
                .header("source", "node1")
                .build();

        assertEquals("deliver", env.getChannel());
        assertEquals("SINGLE_CHAT", env.getEventType());
        assertEquals("msg_001", env.getMessageKey());
        assertEquals("conv_001", env.getBusinessKey());
        assertSame(payload, env.getPayload());
        assertEquals("application/protobuf", env.getContentType());
        assertEquals(now, env.getCreatedAt());
        assertEquals(later, env.getDeliverAt());
        assertEquals(2, env.getHeaders().size());
        assertEquals("abc123", env.getHeaders().get("traceId"));
    }

    @Test
    void buildWithHeadersMap() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope env = MessageEnvelope.builder()
                .channel("persist")
                .payload(payload)
                .headers(Map.of("k1", "v1", "k2", "v2"))
                .header("k3", "v3")
                .build();

        assertEquals(3, env.getHeaders().size());
    }

    @Test
    void rejectNullChannel() {
        assertThrows(IllegalArgumentException.class, () ->
                MessageEnvelope.builder().channel(null).payload(new byte[1]).build());
    }

    @Test
    void rejectEmptyChannel() {
        assertThrows(IllegalArgumentException.class, () ->
                MessageEnvelope.builder().channel("").payload(new byte[1]).build());
    }

    @Test
    void rejectNullPayload() {
        assertThrows(IllegalArgumentException.class, () ->
                MessageEnvelope.builder().channel("test").payload(null).build());
    }

    @Test
    void headersAreImmutable() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        MessageEnvelope env = MessageEnvelope.builder()
                .channel("test")
                .payload(payload)
                .header("key", "val")
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                env.getHeaders().put("new", "value"));
    }

    @Test
    void headersDefensiveCopy() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        Map<String, String> original = new java.util.LinkedHashMap<>();
        original.put("key", "val");

        MessageEnvelope env = MessageEnvelope.builder()
                .channel("test")
                .payload(payload)
                .headers(original)
                .build();

        original.put("key", "modified");
        assertEquals("val", env.getHeaders().get("key"));
    }
}
