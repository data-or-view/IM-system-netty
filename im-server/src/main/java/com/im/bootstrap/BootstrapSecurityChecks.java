package com.im.bootstrap;

import com.im.config.Config;

import java.util.Locale;
import java.util.Set;

final class BootstrapSecurityChecks {

    static final String DEFAULT_TOKEN_SECRET = "im-system-dev-secret-change-in-production";
    static final String DEFAULT_MINIO_ACCESS_KEY = "minioadmin";
    static final String DEFAULT_MINIO_SECRET_KEY = "minioadmin";
    static final String DEFAULT_CALL_API_KEY = "devkey";

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
        if (config.getBoolean("im.security.allow-dev-defaults", false)) {
            return true;
        }
        return LOCAL_ENVS.contains(environment(config));
    }

    private static String environment(Config config) {
        return config.getString("im.env")
                .or(() -> optional(System.getProperty("im.env")))
                .or(() -> optional(System.getenv("IM_ENV")))
                .orElse("dev")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static java.util.Optional<String> optional(String value) {
        return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
    }
}
