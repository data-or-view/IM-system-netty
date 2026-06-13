package com.wzg.idempotency.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON configuration utility for serialization operations.
 */
public class JsonConfig {
    private static final JsonConfig INSTANCE = new JsonConfig();
    private final ObjectMapper objectMapper;

    private JsonConfig() {
        this.objectMapper = new ObjectMapper();
    }

    public static JsonConfig get() {
        return INSTANCE;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public static <T> TypeReference<T> toTypeReference(Class<T> clazz) {
        return new TypeReference<T>() {
            @Override
            public java.lang.reflect.Type getType() {
                return clazz;
            }
        };
    }
}
