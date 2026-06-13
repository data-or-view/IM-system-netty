package com.wzg.idempotency.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementation of a simple LRU Cache based on a LinkedHashMap.
 *
 * @param <K> Type of the keys
 * @param <V> Type of the values
 */
public class LRUCache<K, V> extends LinkedHashMap<K, V> {

    private static final long serialVersionUID = 3108262622672699228L;
    private final int capacity;

    public LRUCache(int capacity) {
        super(capacity * 4 / 3, 0.75f, true);
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
        return size() > this.capacity;
    }
}
