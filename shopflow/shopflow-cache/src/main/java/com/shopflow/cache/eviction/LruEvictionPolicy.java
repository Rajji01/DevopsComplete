package com.shopflow.cache.eviction;

import java.util.LinkedHashSet;

/**
 * LRU via a {@link LinkedHashSet} kept in least→most-recently-used order. On a hit we remove-then-re-add the
 * key, which moves it to the tail; the head is therefore always the least recently used — the victim. All
 * operations are O(1) (LinkedHashSet is a hash map + a doubly-linked list, same trick Caffeine/`LinkedHashMap`
 * use).
 */
public final class LruEvictionPolicy<K> implements EvictionPolicy<K> {

    private final LinkedHashSet<K> order = new LinkedHashSet<>();

    @Override
    public void recordAccess(K key) {
        if (order.remove(key)) {
            order.add(key); // move to the tail = most recently used
        }
    }

    @Override
    public void recordInsert(K key) {
        order.add(key);
    }

    @Override
    public void remove(K key) {
        order.remove(key);
    }

    @Override
    public K evictionCandidate() {
        var it = order.iterator();
        return it.hasNext() ? it.next() : null; // head = least recently used
    }
}
