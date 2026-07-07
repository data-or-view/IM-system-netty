package com.im.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral realtime push event.
 *
 * <p>Core components publish this DTO without depending on WebSocket classes.
 * Transport adapters decide how to encode it for a concrete protocol.</p>
 */
public record PushEvent(String op, Object data) {

    public PushEvent {
        Objects.requireNonNull(op, "op");
    }

    public Map<String, Object> toEnvelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("op", op);
        envelope.put("data", data);
        return envelope;
    }
}
