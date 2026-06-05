package com.im.common.id;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates compact, URL-safe server-side business IDs.
 *
 * <p>Format: {@code <prefix>_<base36 timestamp>_<random base36>}. Prefixes are
 * intentionally centralized in {@link IdPrefix} so public IDs stay consistent
 * across modules and clients never need to create domain IDs themselves.</p>
 */
public final class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RANDOM_BOUND = 36L * 36 * 36 * 36 * 36 * 36 * 36 * 36;

    private IdGenerator() {
        // utility class
    }

    public static String groupId() {
        return next(IdPrefix.GROUP);
    }

    public static String userId() {
        return next(IdPrefix.USER);
    }

    public static String fileId() {
        return next(IdPrefix.FILE);
    }

    public static String roomId() {
        return next(IdPrefix.ROOM);
    }

    public static String messageId() {
        return next(IdPrefix.MESSAGE);
    }

    public static String sessionId() {
        return next(IdPrefix.SESSION);
    }

    public static String next(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("id prefix must not be blank");
        }
        String normalizedPrefix = prefix.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        long random = nextPositiveLong(RANDOM_BOUND);
        return normalizedPrefix + "_" + Long.toString(now, 36) + "_" + padBase36(random, 8);
    }

    private static long nextPositiveLong(long bound) {
        long value = RANDOM.nextLong();
        if (value == Long.MIN_VALUE) value = 0;
        return Math.abs(value) % bound;
    }

    private static String padBase36(long value, int width) {
        String text = Long.toString(value, 36);
        if (text.length() >= width) return text;
        return "0".repeat(width - text.length()) + text;
    }
}
