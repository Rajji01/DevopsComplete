package com.shopflow.cache.eviction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LFU: evict the Least Frequently Used. Each key carries a hit count; the victim is the lowest count, with
 * insertion order as the tie-break (so among equally-cold keys the oldest goes first).
 *
 * This is the teaching-clear version: {@code evictionCandidate} scans for the minimum — O(n). Production
 * LFU keeps keys bucketed by frequency for O(1) (and real systems use *windowed* TinyLFU, as Caffeine does,
 * so a one-off burst can't permanently crown a key). The scan is fine here where clarity beats micro-perf.
 */
public final class LfuEvictionPolicy<K> implements EvictionPolicy<K> {

    // LinkedHashMap preserves insertion order -> deterministic tie-break among equal frequencies
    private final Map<K, Long> frequency = new LinkedHashMap<>();

    @Override
    public void recordAccess(K key) {
        frequency.computeIfPresent(key, (k, f) -> f + 1);
    }

    @Override
    public void recordInsert(K key) {
        frequency.put(key, 1L);
    }

    @Override
    public void remove(K key) {
        frequency.remove(key);
    }

    @Override
    public K evictionCandidate() {
        K victim = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<K, Long> e : frequency.entrySet()) {
            if (e.getValue() < min) {       // strict '<' keeps the FIRST-inserted among ties (LinkedHashMap order)
                min = e.getValue();
                victim = e.getKey();
            }
        }
        return victim;
    }
}
