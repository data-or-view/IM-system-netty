package com.im.bootstrap.ws;

import java.util.Map;
import java.util.Objects;

public record WsPushEvent(String op, Object data) {

    public WsPushEvent {
        Objects.requireNonNull(op, "op");
    }

    public Map<String, Object> toEnvelope() {
        return Map.of("op", op, "data", data);
    }
}
