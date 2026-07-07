package com.im.core.ratelimit;

import com.im.api.ApiRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

/**
 * One operation-specific rate limit rule.
 */
public record RateLimitRule(String name,
                            RateLimitScope scope,
                            int limit,
                            Duration window,
                            String keyPrefix,
                            Function<ApiRequest, String> identityExtractor) {

    private static final int MAX_READABLE_IDENTITY_CHARS = 160;

    public RateLimitRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(identityExtractor, "identityExtractor");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }

    public String key(ApiRequest request) {
        return keyPrefix + name + ":" + scope.keyPart() + ":" + normalizeIdentity(identityExtractor.apply(request));
    }

    private static String normalizeIdentity(String identity) {
        String value = identity == null || identity.isBlank() ? "unknown" : identity.trim();
        if (isReadableRedisKeyPart(value)) {
            return value;
        }
        return "sha256:" + sha256(value);
    }

    private static boolean isReadableRedisKeyPart(String value) {
        if (value.length() > MAX_READABLE_IDENTITY_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
