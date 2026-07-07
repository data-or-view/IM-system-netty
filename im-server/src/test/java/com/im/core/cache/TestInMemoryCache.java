package com.im.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-only cache fake. Production wiring must use Redis-backed caches.
 */
public final class TestInMemoryCache<K, V> implements Cache<K, V> {

    private final ConcurrentMap<K, V> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public Map<K, Optional<V>> getAllPresent(Set<?> keys) {
        Map<K, Optional<V>> result = new LinkedHashMap<>();
        for (Object key : keys) {
            @SuppressWarnings("unchecked")
            K typedKey = (K) key;
            result.put(typedKey, get(typedKey));
        }
        return result;
    }

    @Override
    public void put(K key, V value) {
        entries.put(key, value);
    }

    @Override
    public boolean putIfAbsent(K key, V value) {
        return entries.putIfAbsent(key, value) == null;
    }

    @Override
    public boolean invalidate(K key) {
        return entries.remove(key) != null;
    }

    @Override
    public void invalidateAll(Set<?> keys) {
        for (Object key : keys) {
            entries.remove(key);
        }
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public long estimatedSize() {
        return entries.size();
    }

    @Override
    public CacheStats stats() {
        return CacheStats.EMPTY;
    }
}
