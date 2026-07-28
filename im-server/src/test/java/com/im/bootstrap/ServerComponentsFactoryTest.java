package com.im.bootstrap;

import com.im.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerComponentsFactoryTest {

    @Test
    void rejectsConfigurationWithoutRedis() {
        Config config = new TestConfig(Map.of("im.db.enabled", "true"));

        assertThrows(IllegalStateException.class, () -> ServerComponentsFactory.create(config));
    }

    @Test
    void resolvesClusterSafeGroupCallLayoutFromDefaultAndEnvironmentAlias() {
        assertEquals("tagged-v3", ServerComponentsFactory.groupCallRedisKeyLayout(new TestConfig(Map.of())));
        assertEquals("legacy", ServerComponentsFactory.groupCallRedisKeyLayout(
                new TestConfig(Map.of("im.call.group.redis.key.layout", "legacy"))));
        assertEquals("tagged-v3", ServerComponentsFactory.groupCallRedisKeyLayout(
                new TestConfig(Map.of(
                        "im.call.group.redis.key.layout", "legacy",
                        "im.call.group.redis-key-layout", "tagged-v3"))));
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
