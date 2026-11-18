package com.shopflow.cache;

/**
 * A snapshot of what the cache has been doing. The one number that matters is {@link #hitRate()} — a cache
 * earning its keep sits high (0.8–0.99); a low hit rate means the cache is cost without benefit (wrong keys,
 * too-short TTL, or too-small capacity causing thrash). {@link #evictions} climbing fast = capacity too small.
 */
public record CacheStats(long hits, long misses, long loads, long evictions) {

    public long requestCount() {
        return hits + misses;
    }

    public double hitRate() {
        long total = requestCount();
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
