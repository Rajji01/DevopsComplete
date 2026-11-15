package com.shopflow.cache.eviction;

/**
 * Tracks usage so the cache can pick a victim when it is full. Deliberately NOT thread-safe: the owning
 * cache calls these only while holding its own lock, so adding locking here would be redundant contention.
 *
 * The cache calls {@code recordInsert} on a new key, {@code recordAccess} on a hit, {@code remove} on
 * invalidate/expire, and {@code evictionCandidate} to choose what to drop when over capacity.
 */
public interface EvictionPolicy<K> {

    void recordAccess(K key);

    void recordInsert(K key);

    void remove(K key);

    /** The next key to evict, or {@code null} if the policy is tracking nothing. */
    K evictionCandidate();
}
