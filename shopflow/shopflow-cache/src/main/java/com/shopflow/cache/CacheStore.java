package com.shopflow.cache;

import java.util.Optional;

/**
 * The raw key→value store under a cache: hold values, expire them, evict when full. It knows nothing about
 * loaders or write strategies — that is {@link LoadingCache}'s job. Splitting it this way means the same
 * strategy layer can sit on an in-memory store ({@link BoundedLocalCache}) or, in prod, a Redis-backed one.
 *
 * {@code getIfPresent} returning empty is a genuine miss (absent or expired). A present {@code Optional}
 * whose VALUE is null is a deliberately cached "miss" (negative caching) — only {@link LoadingCache} needs
 * to tell those apart, so it stores a holder, not the bare value.
 */
public interface CacheStore<K, V> {

    Optional<V> getIfPresent(K key);

    /** @param ttlMillis time-to-live; {@code <= 0} means "no expiry". */
    void put(K key, V value, long ttlMillis);

    void invalidate(K key);

    void invalidateAll();

    int size();

    CacheStats stats();
}
