package com.im.core.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeCacheTest {

    @Test
    void readFailuresFallThroughAsMisses() {
        SafeCache<String, String> cache = new SafeCache<>(new FailingCache<>(), "test-cache");

        assertTrue(cache.get("k1").isEmpty());
        assertEquals(Map.of("k1", Optional.empty()), cache.getAllPresent(Set.of("k1")));
    }

    @Test
    void writeAndInvalidateFailuresDoNotEscape() {
        SafeCache<String, String> cache = new SafeCache<>(new FailingCache<>(), "test-cache");

        assertDoesNotThrow(() -> cache.put("k1", "v1"));
        assertFalse(cache.putIfAbsent("k1", "v1"));
        assertFalse(cache.invalidate("k1"));
        assertDoesNotThrow(() -> cache.invalidateAll(Set.of("k1")));
        assertDoesNotThrow(cache::clear);
        assertEquals(0, cache.estimatedSize());
        assertEquals(CacheStats.EMPTY, cache.stats());
    }

    private static final class FailingCache<K, V> implements Cache<K, V> {
        @Override public Optional<V> get(K key) { throw failure(); }
        @Override public Map<K, Optional<V>> getAllPresent(Set<?> keys) { throw failure(); }
        @Override public void put(K key, V value) { throw failure(); }
        @Override public boolean putIfAbsent(K key, V value) { throw failure(); }
        @Override public boolean invalidate(K key) { throw failure(); }
        @Override public void invalidateAll(Set<?> keys) { throw failure(); }
        @Override public void clear() { throw failure(); }
        @Override public long estimatedSize() { throw failure(); }
        @Override public CacheStats stats() { throw failure(); }

        private IllegalStateException failure() {
            return new IllegalStateException("cache backend down");
        }
    }
}
