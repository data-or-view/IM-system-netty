package com.im.core.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * Result returned by a rate limiter for one rule key.
 */
public record RateLimitDecision(boolean allowed,
                                long currentCount,
                                long remaining,
                                Duration retryAfter) {

    public RateLimitDecision {
        retryAfter = Objects.requireNonNullElse(retryAfter, Duration.ZERO);
        remaining = Math.max(remaining, 0);
    }

    public static RateLimitDecision allowed(long currentCount, long remaining, Duration retryAfter) {
        return new RateLimitDecision(true, currentCount, remaining, retryAfter);
    }

    public static RateLimitDecision rejected(long currentCount, long remaining, Duration retryAfter) {
        return new RateLimitDecision(false, currentCount, remaining, retryAfter);
    }
}
