package com.shopflow.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reservoir sampling (Vitter's Algorithm R): keep a uniform random sample of size k from a stream whose
 * length you DON'T know in advance and can't hold in memory. Fill the reservoir with the first k items;
 * thereafter the i-th item replaces a random existing slot with probability k/i. The invariant that makes
 * it work: after seeing n items, every one of them is in the reservoir with probability exactly k/n — one
 * pass, O(k) memory, no second visit. Used for log sampling, A/B bucketing, picking N rows from a cursor.
 */
public final class ReservoirSampler<T> {

    private final int capacity;
    private final Random random;
    private final List<T> reservoir = new ArrayList<>();
    private long seen = 0;

    public ReservoirSampler(int capacity, Random random) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.random = random;
    }

    public void offer(T item) {
        seen++;
        if (reservoir.size() < capacity) {
            reservoir.add(item);                       // still filling
            return;
        }
        long j = Math.floorMod(random.nextLong(), seen); // uniform in [0, seen)
        if (j < capacity) {
            reservoir.set((int) j, item);               // evict a random slot with probability k/seen
        }
    }

    public List<T> sample() {
        return new ArrayList<>(reservoir);
    }

    public long seen() {
        return seen;
    }
}
