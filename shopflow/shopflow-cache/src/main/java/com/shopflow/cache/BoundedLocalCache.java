package com.shopflow.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

import com.shopflow.cache.eviction.EvictionPolicy;

/**
 * An in-memory, bounded store: at most {@code capacity} entries, each with an optional TTL, evicting via a
 * pluggable {@link EvictionPolicy} when full. The clock is injected so tests can expire entries without
 * sleeping.
 *
 * Correctness over raw throughput: every method is {@code synchronized}, because the map and the eviction
 * policy must move together (evict-then-insert can't interleave with another thread's access reordering).
 * Caffeine does this lock-free with far more machinery; here one lock keeps it obviously correct.
 *
 * TTL JITTER (avalanche defence): if many keys are written together with the same TTL they would all expire
 * at the same instant, dumping a synchronised thundering herd on the database. Adding a small random spread
 * to each TTL scatters those expiries.
 */
public final class BoundedLocalCache<K, V> implements CacheStore<K, V> {

    private record Entry<V>(V value, long expireAt) {} // expireAt == Long.MAX_VALUE => never expires

    private final int capacity;
    private final EvictionPolicy<K> policy;
    private final LongSupplier clock;
    private final double jitterFraction;

    private final Map<K, Entry<V>> map = new HashMap<>();
    private long hits;
    private long misses;
    private long evictions;

    public BoundedLocalCache(int capacity, EvictionPolicy<K> policy, LongSupplier clock, double jitterFraction) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.policy = policy;
        this.clock = clock;
        this.jitterFraction = jitterFraction;
    }

    @Override
    public synchronized Optional<V> getIfPresent(K key) {
        Entry<V> e = map.get(key);
        if (e == null) {
            misses++;
            return Optional.empty();
        }
        if (clock.getAsLong() >= e.expireAt()) {   // lazily evict on read — expired is a miss
            map.remove(key);
            policy.remove(key);
            misses++;
            return Optional.empty();
        }
        policy.recordAccess(key);
        hits++;
        return Optional.ofNullable(e.value());
    }

    @Override
    public synchronized void put(K key, V value, long ttlMillis) {
        boolean isNew = !map.containsKey(key);
        if (isNew && map.size() >= capacity) {
            evictOne();
        }
        long expireAt = ttlMillis <= 0 ? Long.MAX_VALUE : clock.getAsLong() + jittered(ttlMillis);
        map.put(key, new Entry<>(value, expireAt));
        if (isNew) {
            policy.recordInsert(key);
        } else {
            policy.recordAccess(key);
        }
    }

    @Override
    public synchronized void invalidate(K key) {
        if (map.remove(key) != null) {
            policy.remove(key);
        }
    }

    @Override
    public synchronized void invalidateAll() {
        for (K k : new ArrayList<>(map.keySet())) {
            policy.remove(k);
        }
        map.clear();
    }

    @Override
    public synchronized int size() {
        return map.size();
    }

    @Override
    public synchronized CacheStats stats() {
        return new CacheStats(hits, misses, 0, evictions); // 'loads' is a LoadingCache concern
    }

    private void evictOne() {
        K victim = policy.evictionCandidate();
        if (victim != null) {
            map.remove(victim);
            policy.remove(victim);
            evictions++;
        }
    }

    private long jittered(long ttl) {
        if (jitterFraction <= 0) {
            return ttl;
        }
        long spread = (long) (ttl * jitterFraction);
        return ttl + (spread <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spread + 1));
    }
}
