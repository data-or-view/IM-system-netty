package com.im.bootstrap;

import com.im.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BootstrapSecurityChecksTest {

    @Test
    void rejectsDevelopmentDefaultsOutsideLocalEnvironment() {
        Config config = new TestConfig(Map.of("im.env", "prod"));

        assertThrows(IllegalStateException.class, () -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.token.secret",
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET,
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET));
    }

    @Test
    void allowsDevelopmentDefaultsInLocalEnvironment() {
        Config config = new TestConfig(Map.of("im.env", "macbook-dev"));

        assertDoesNotThrow(() -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.token.secret",
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET,
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET));
    }

    @Test
    void explicitOverrideAllowsDevelopmentDefaults() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.security.allow-dev-defaults", "true"));

        assertDoesNotThrow(() -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.minio.secret-key",
                BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY,
                BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY));
    }

    @Test
    void rebuildSchemaIsRejectedOutsideLocalEnvironment() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.db.schema", "rebuild"));

        assertThrows(IllegalStateException.class,
                () -> DatabaseComponentsFactory.requireAllowedSchemaMode(config));
    }

    @Test
    void rebuildSchemaWithWhitespaceIsRejectedOutsideLocalEnvironment() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.db.schema", " rebuild "));

        assertThrows(IllegalStateException.class,
                () -> DatabaseComponentsFactory.requireAllowedSchemaMode(config));
    }

    @Test
    void rebuildSchemaIsAllowedInExplicitLocalEnvironment() {
        Config config = new TestConfig(Map.of(
                "im.env", "test",
                "im.db.schema", "rebuild"));

        assertDoesNotThrow(() -> DatabaseComponentsFactory.requireAllowedSchemaMode(config));
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
