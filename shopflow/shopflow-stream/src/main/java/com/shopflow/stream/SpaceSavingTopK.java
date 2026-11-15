package com.shopflow.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Space-Saving top-K: find the heaviest hitters in a stream using only K counters, one pass. When a new item
 * arrives and all K slots are taken, EVICT the current minimum and hand its slot to the newcomer with
 * {@code count = min + 1} — so a genuinely frequent item quickly climbs past the churn of one-off items,
 * while the {@code error} field remembers it may have inherited up to {@code min} spurious counts (its true
 * count is in {@code [count - error, count]}). Used for "top N products / IPs / search terms" in fixed memory.
 *
 * (Finding the min is O(K) here for clarity; the real Stream-Summary structure makes it O(1).)
 */
public final class SpaceSavingTopK<T> {

    public record Entry<T>(T item, long count, long error) {}

    private static final class Counter {
        long count;
        long error;

        Counter(long count, long error) {
            this.count = count;
            this.error = error;
        }
    }

    private final int capacity;
    private final Map<T, Counter> counters = new HashMap<>();

    public SpaceSavingTopK(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void offer(T item) {
        Counter c = counters.get(item);
        if (c != null) {
            c.count++;
            return;
        }
        if (counters.size() < capacity) {
            counters.put(item, new Counter(1, 0));
            return;
        }
        // full: evict the minimum, give its (inflated) count to the newcomer
        T minKey = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<T, Counter> e : counters.entrySet()) {
            if (e.getValue().count < min) {
                min = e.getValue().count;
                minKey = e.getKey();
            }
        }
        counters.remove(minKey);
        counters.put(item, new Counter(min + 1, min));
    }

    /** All tracked items, heaviest first; take the first k for the top-K. */
    public List<Entry<T>> top() {
        List<Entry<T>> out = new ArrayList<>();
        for (Map.Entry<T, Counter> e : counters.entrySet()) {
            out.add(new Entry<>(e.getKey(), e.getValue().count, e.getValue().error));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }
}
