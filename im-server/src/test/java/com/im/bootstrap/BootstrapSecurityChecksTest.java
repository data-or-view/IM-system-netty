package com.im.bootstrap;

import com.im.config.Config;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void missingEnvironmentDoesNotPermitKnownDevelopmentSecret() {
        Config config = new TestConfig(Map.of());

        assertThrows(IllegalStateException.class, () -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.token.secret",
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET,
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET));
    }

    @Test
    void explicitFullOverrideAllowsDevelopmentDefaults() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.security.allow-development-defaults", "true"));

        assertDoesNotThrow(() -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.minio.secret-key",
                BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY,
                BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY));
        assertTrue(BootstrapSecurityChecks.allowsDevDefaults(config));
    }

    @Test
    void legacyDevelopmentOverrideDoesNotPermitUnsafeCredentials() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.security.allow-dev-defaults", "true"));

        assertThrows(IllegalStateException.class, () -> BootstrapSecurityChecks.requireSafeSecret(
                config,
                "im.token.secret",
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET,
                BootstrapSecurityChecks.DEFAULT_TOKEN_SECRET));
    }

    @Test
    void storageCredentialsRejectEitherDefaultOutsideExplicitLocalEnvironment() {
        Config config = new TestConfig(Map.of("im.env", "prod"));

        assertThrows(IllegalStateException.class, () -> StorageComponentsFactory.requireMinioCredentials(
                config, BootstrapSecurityChecks.DEFAULT_MINIO_ACCESS_KEY, "custom-secret"));
        assertThrows(IllegalStateException.class, () -> StorageComponentsFactory.requireMinioCredentials(
                config, "custom-access-key", BootstrapSecurityChecks.DEFAULT_MINIO_SECRET_KEY));
    }

    @Test
    void enabledCallRejectsKnownDevelopmentCredentialsOutsideExplicitLocalEnvironment() {
        Config config = new TestConfig(Map.of(
                "im.env", "prod",
                "im.call.enabled", "true",
                "im.call.api-key", BootstrapSecurityChecks.DEFAULT_CALL_API_KEY,
                "im.call.api-secret", BootstrapSecurityChecks.DEFAULT_CALL_API_SECRET));

        assertThrows(IllegalStateException.class,
                () -> ServerComponentsFactory.requireCallCredentials(config));
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
