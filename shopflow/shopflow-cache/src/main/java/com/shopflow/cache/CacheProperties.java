package com.shopflow.cache;

/**
 * The knobs, all in one place (map these from {@code cache.*} config in a Spring app).
 *
 * @param capacity          max entries before eviction kicks in
 * @param eviction          which entry to drop when full (LRU / LFU / FIFO)
 * @param writePolicy       what a write does to cache vs system-of-record
 * @param ttlMillis         entry time-to-live; {@code <= 0} = no expiry
 * @param jitterFraction    spread added to each TTL (0.1 = up to +10%) to avoid synchronized expiry (avalanche)
 * @param negativeTtlMillis how long to cache a "not found"; {@code <= 0} = negative caching off
 */
public record CacheProperties(int capacity, EvictionType eviction, WritePolicy writePolicy,
                              long ttlMillis, double jitterFraction, long negativeTtlMillis) {

    public static CacheProperties defaults() {
        return new CacheProperties(1_000, EvictionType.LRU, WritePolicy.WRITE_THROUGH, 60_000, 0.10, 5_000);
    }
}
