package com.shopflow.cache.eviction;

import java.util.LinkedHashSet;

/**
 * FIFO: evict in insertion order, regardless of use. Same structure as LRU but {@code recordAccess} is a
 * no-op — a key's position never changes once inserted, so the head is always the OLDEST entry. The flaw is
 * right there: a constantly-used key still gets evicted the moment it is the oldest. Here mostly to contrast
 * with LRU/LFU.
 */
public final class FifoEvictionPolicy<K> implements EvictionPolicy<K> {

    private final LinkedHashSet<K> order = new LinkedHashSet<>();

    @Override
    public void recordAccess(K key) {
        // intentionally nothing: FIFO ignores usage
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
        return it.hasNext() ? it.next() : null; // head = first inserted
    }
}
