package com.im.bootstrap;

import com.im.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class BootstrapSecurityChecks {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSecurityChecks.class);
    static final String DEFAULT_TOKEN_SECRET = "im-system-dev-secret-change-in-production";
    static final String DEFAULT_MINIO_ACCESS_KEY = "minioadmin";
    static final String DEFAULT_MINIO_SECRET_KEY = "minioadmin";
    static final String DEFAULT_CALL_API_KEY = "devkey";
    static final String DEFAULT_CALL_API_SECRET = "im-system-livekit-secret-2024";

    private static final Set<String> LOCAL_ENVS = Set.of(
            "dev", "local", "test", "macbook-dev", "development");

    private BootstrapSecurityChecks() {
    }

    static void requireSafeSecret(Config config, String key, String value, String... forbiddenValues) {
        if (allowsDevDefaults(config)) {
            return;
        }
        String normalized = value != null ? value.trim() : "";
        if (normalized.isEmpty()) {
            throw new IllegalStateException(key + " must not be empty outside local development");
        }
        for (String forbidden : forbiddenValues) {
            if (normalized.equals(forbidden)) {
                throw new IllegalStateException(key + " uses an unsafe development default outside local development");
            }
        }
    }

    static boolean allowsDevDefaults(Config config) {
        if (config.getBoolean("im.security.allow-development-defaults", false)) {
            log.warn("Development defaults explicitly enabled by im.security.allow-development-defaults");
            return true;
        }
        return explicitEnvironment(config).map(LOCAL_ENVS::contains).orElse(false);
    }

    private static Optional<String> explicitEnvironment(Config config) {
        return config.getString("im.env")
                .or(() -> optional(System.getProperty("im.env")))
                .or(() -> optional(System.getenv("IM_ENV")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
