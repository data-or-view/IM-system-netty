package com.im.common.trace;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

public final class RequestIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestIds() {
    }

    public static String next() {
        long millis = Instant.now().toEpochMilli();
        long random = RANDOM.nextLong() & Long.MAX_VALUE;
        return "req_" + Long.toString(millis, 36) + "_" + Long.toString(random, 36).toLowerCase(Locale.ROOT);
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
