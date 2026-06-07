package com.im.bootstrap.http;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CorsConfig {

    private static final Set<String> ALLOWED_ORIGINS = new LinkedHashSet<>(Set.of(
            "http://127.0.0.1:39073",
            "http://localhost:39073"
    ));

    private CorsConfig() {
    }

    public static void configure(String allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return;
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .forEach(origins::add);
        if (!origins.isEmpty()) {
            synchronized (ALLOWED_ORIGINS) {
                ALLOWED_ORIGINS.clear();
                ALLOWED_ORIGINS.addAll(origins);
            }
        }
    }

    static String allowOrigin(String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return "*";
        }
        synchronized (ALLOWED_ORIGINS) {
            if (ALLOWED_ORIGINS.contains("*") || ALLOWED_ORIGINS.contains(requestOrigin)) {
                return requestOrigin;
            }
        }
        return "";
    }
}
