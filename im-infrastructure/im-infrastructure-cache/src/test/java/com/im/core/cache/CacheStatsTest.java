package com.im.core.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheStatsTest {

    @Test
    void emptyStatsShouldHaveZeroCounters() {
        assertEquals(0, CacheStats.EMPTY.hitCount());
        assertEquals(0, CacheStats.EMPTY.missCount());
        assertEquals(0, CacheStats.EMPTY.evictionCount());
    }

    @Test
    void emptyStatsShouldHaveHitRateOne() {
        assertEquals(1.0, CacheStats.EMPTY.hitRate());
    }

    @Test
    void shouldCalculateHitRate() {
        var stats = new CacheStats(3, 1, 0, 0, 0);
        assertEquals(0.75, stats.hitRate(), 0.0001);
    }

    @Test
    void hitRateShouldBeOneWhenNoRequests() {
        var stats = new CacheStats(0, 0, 0, 0, 0);
        assertEquals(1.0, stats.hitRate());
    }

    @Test
    void shouldAggregateStatsWithPlus() {
        var a = new CacheStats(3, 1, 2, 5, 1);
        var b = new CacheStats(7, 2, 3, 5, 0);
        var sum = a.plus(b);

        assertEquals(10, sum.hitCount());
        assertEquals(3, sum.missCount());
        assertEquals(5, sum.evictionCount());
        assertEquals(10, sum.loadSuccessCount());
        assertEquals(1, sum.loadFailureCount());
    }

    @Test
    void plusShouldNotMutateOriginalStats() {
        var a = new CacheStats(3, 1, 0, 0, 0);
        var b = new CacheStats(7, 2, 0, 0, 0);
        a.plus(b);

        assertEquals(3, a.hitCount());
        assertEquals(7, b.hitCount());
    }

    @Test
    void shouldBeImmutable() {
        var stats = new CacheStats(1, 2, 3, 4, 5);

        assertEquals(1, stats.hitCount());
        assertEquals(2, stats.missCount());
        assertEquals(3, stats.evictionCount());
        assertEquals(4, stats.loadSuccessCount());
        assertEquals(5, stats.loadFailureCount());
    }

    @Test
    void equalsAndHashCode() {
        var a = new CacheStats(1, 2, 3, 4, 5);
        var b = new CacheStats(1, 2, 3, 4, 5);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentStatsShouldNotBeEqual() {
        var a = new CacheStats(1, 2, 3, 4, 5);
        var b = new CacheStats(9, 2, 3, 4, 5);

        assertNotEquals(a, b);
    }

    @Test
    void toStringShouldContainHitRate() {
        var stats = new CacheStats(3, 1, 0, 0, 0);
        String s = stats.toString();
        assertTrue(s.contains("0.75") || s.contains("0,75"), "toString should contain hit rate");
    }
}
