package com.im.core.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRateLimiterTest {

    @Test
    void redisReplyIsConvertedToDecision() {
        RateLimitDecision allowed = RedisRateLimiter.toDecision(List.of(1L, 2L, 8L, 60000L));
        RateLimitDecision rejected = RedisRateLimiter.toDecision(List.of(0L, 7L, 0L, 12000L));

        assertTrue(allowed.allowed());
        assertEquals(2L, allowed.currentCount());
        assertEquals(8L, allowed.remaining());
        assertEquals(Duration.ofSeconds(60), allowed.retryAfter());

        assertFalse(rejected.allowed());
        assertEquals(7L, rejected.currentCount());
        assertEquals(0L, rejected.remaining());
        assertEquals(Duration.ofSeconds(12), rejected.retryAfter());
    }
}
