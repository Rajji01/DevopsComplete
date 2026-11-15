package com.shopflow.stream;

import java.util.HashMap;
import java.util.Map;

/**
 * Misra-Gries frequent-items: with {@code k-1} counters it finds every item whose true frequency exceeds
 * {@code n/k} (n = stream length). The rule is elegantly simple: count a tracked item; if there's a free
 * counter, start one; otherwise DECREMENT every counter and drop any that hit zero (one new item "cancels"
 * one of each other). Survivors are the CANDIDATES — a classic over-approximation: everything truly frequent
 * is in the set, but the set may also hold a few that aren't, so a confirming second pass is needed for
 * exact counts. The ancestor of Count-Min and Space-Saving.
 */
public final class MisraGries<T> {

    private final int maxCounters; // k-1
    private final Map<T, Long> counters = new HashMap<>();

    /** @param k finds all items with frequency &gt; n/k; uses k-1 counters. */
    public MisraGries(int k) {
        if (k < 2) {
            throw new IllegalArgumentException("k must be >= 2");
        }
        this.maxCounters = k - 1;
    }

    public void offer(T item) {
        if (counters.containsKey(item)) {
            counters.merge(item, 1L, Long::sum);
        } else if (counters.size() < maxCounters) {
            counters.put(item, 1L);
        } else {
            counters.replaceAll((key, v) -> v - 1); // the newcomer cancels one of each
            counters.values().removeIf(v -> v <= 0);
        }
    }

    /** Candidate frequent items (a superset of the true frequent set — confirm with a second pass). */
    public Map<T, Long> candidates() {
        return new HashMap<>(counters);
    }
}
