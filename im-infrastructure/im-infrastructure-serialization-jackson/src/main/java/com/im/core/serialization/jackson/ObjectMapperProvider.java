package com.im.core.serialization.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 共享 Jackson ObjectMapper 工厂。
 *
 * <p>统一管理 ObjectMapper 配置，避免业务代码中散落多个实例。
 * 所有 Jackson 序列化行为由此集中控制。
 */
public final class ObjectMapperProvider {

    private static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private ObjectMapperProvider() {}

    public static ObjectMapper get() {
        return INSTANCE;
    }
}
