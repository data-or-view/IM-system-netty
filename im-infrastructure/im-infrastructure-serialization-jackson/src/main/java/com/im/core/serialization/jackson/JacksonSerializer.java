package com.im.core.serialization.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.serialization.Serializer;

/**
 * 基于 Jackson 的序列化实现，序列化结果为 JSON 字符串。
 *
 * @param <T> Java 类型
 */
public class JacksonSerializer<T> implements Serializer<T, String> {

    private final ObjectMapper objectMapper;

    public JacksonSerializer() {
        this(new ObjectMapper());
    }

    public JacksonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(T source) {
        try {
            return objectMapper.writeValueAsString(source);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize: " + source, e);
        }
    }

    @Override
    public T deserialize(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize: " + raw, e);
        }
    }
}
