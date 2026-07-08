package com.im.common.trace;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}(?:-.+)?$");

    private TraceIds() {
    }

    public static String next() {
        byte[] bytes = new byte[16];
        String traceId;
        do {
            RANDOM.nextBytes(bytes);
            traceId = toLowerHex(bytes);
        } while (isAllZeros(traceId));
        return traceId;
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return TRACE_ID.matcher(trimmed).matches() && !isAllZeros(trimmed) ? trimmed : null;
    }

    public static String fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        return normalize(matcher.group(1));
    }

    public static String firstValid(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String toLowerHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(chars);
    }

    private static boolean isAllZeros(String traceId) {
        return "00000000000000000000000000000000".equals(traceId);
    }
}
