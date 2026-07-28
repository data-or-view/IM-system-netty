package com.im.bootstrap;

import com.im.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageComponentsFactoryTest {

    @Test
    void uploadLimitPrefersExplicitPrimaryKeyOverLegacyFallback() {
        assertEquals(12L, StorageComponentsFactory.resolveMaxUploadBytes(new TestConfig(Map.of(
                "im.file.max-upload-bytes", "12",
                "im.minio.max-file-size", "8"
        ))));
    }

    @Test
    void uploadLimitUsesLegacyKeyWhenPrimaryIsNotConfigured() {
        assertEquals(8L, StorageComponentsFactory.resolveMaxUploadBytes(new TestConfig(Map.of(
                "im.minio.max-file-size", "8"
        ))));
    }

    @Test
    void uploadLimitUsesDefaultOnlyWhenNeitherKeyIsConfigured() {
        assertEquals(100L * 1024 * 1024,
                StorageComponentsFactory.resolveMaxUploadBytes(new TestConfig(Map.of())));
    }

    private record TestConfig(Map<String, String> values) implements Config {
        @Override public Optional<String> getString(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public Optional<Integer> getInt(String key) { return Optional.ofNullable(values.get(key)).map(Integer::parseInt); }
        @Override public Optional<Long> getLong(String key) { return Optional.ofNullable(values.get(key)).map(Long::parseLong); }
        @Override public Optional<Boolean> getBoolean(String key) { return Optional.ofNullable(values.get(key)).map(Boolean::parseBoolean); }
        @Override public Optional<Duration> getDuration(String key) { return Optional.empty(); }
        @Override public boolean hasKey(String key) { return values.containsKey(key); }
    }
}
