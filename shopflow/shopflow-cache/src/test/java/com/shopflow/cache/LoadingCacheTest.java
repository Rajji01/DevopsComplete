package com.shopflow.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/** The strategy layer: read-through, single-flight, negative caching, and each write policy. */
class LoadingCacheTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;

    private CacheProperties props(WritePolicy writePolicy, long negativeTtl) {
        return new CacheProperties(100, EvictionType.LRU, writePolicy, 60_000, 0.0, negativeTtl);
    }

    @Test
    void readThrough_loadsOnceThenServesFromCache() {
        AtomicInteger loads = new AtomicInteger();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.WRITE_THROUGH, 0), clock,
                k -> { loads.incrementAndGet(); return "V-" + k; }, null, Runnable::run);
        assertThat(cache.get("k")).contains("V-k");
        assertThat(cache.get("k")).contains("V-k");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void negativeCaching_absorbsRepeatedMissesUntilItExpires() {
        AtomicInteger loads = new AtomicInteger();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.WRITE_THROUGH, 5_000), clock,
                k -> { loads.incrementAndGet(); return null; }, null, Runnable::run); // always "not found"
        assertThat(cache.get("missing")).isEmpty();
        assertThat(cache.get("missing")).isEmpty();
        assertThat(loads.get()).isEqualTo(1);              // the miss itself was cached
        now.addAndGet(5_000);                              // negative TTL elapses
        assertThat(cache.get("missing")).isEmpty();
        assertThat(loads.get()).isEqualTo(2);              // now it reloads
    }

    @Test
    void singleFlight_collapsesConcurrentLoadsOfTheSameKey() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.WRITE_THROUGH, 0), clock,
                k -> { loads.incrementAndGet(); sleep(50); return "V"; }, null, Runnable::run);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> { await(start); cache.get("hot"); done.countDown(); });
        }
        start.countDown();                                 // release all at once -> they race the same miss
        done.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(loads.get()).isEqualTo(1);              // 8 callers, exactly ONE DB load
    }

    @Test
    void writeThrough_writesSystemOfRecordAndPopulatesCache() {
        var sor = new ConcurrentHashMap<String, String>();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.WRITE_THROUGH, 0), clock,
                sor::get, sor::put, Runnable::run);
        cache.put("k", "V");
        assertThat(sor).containsEntry("k", "V");           // SoR written synchronously
        sor.remove("k");                                   // prove the next get is served from cache...
        assertThat(cache.get("k")).contains("V");          // ...not reloaded from the (now empty) SoR
    }

    @Test
    void writeBehind_cachesImmediatelyAndFlushesToSystemOfRecord() {
        var sor = new ConcurrentHashMap<String, String>();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.WRITE_BEHIND, 0), clock,
                sor::get, sor::put, Runnable::run);        // inline executor -> flush is synchronous here
        cache.put("k", "V");
        assertThat(cache.getIfPresent("k")).contains("V"); // in cache right away
        assertThat(sor).containsEntry("k", "V");           // and drained to the SoR
    }

    @Test
    void invalidateWrite_dropsCachedValueSoTheNextReadReloadsFresh() {
        var sor = new ConcurrentHashMap<String, String>();
        sor.put("k", "OLD");
        AtomicInteger loads = new AtomicInteger();
        var cache = CacheFactory.<String, String>create(props(WritePolicy.INVALIDATE, 0), clock,
                k -> { loads.incrementAndGet(); return sor.get(k); }, sor::put, Runnable::run);
        assertThat(cache.get("k")).contains("OLD");        // load #1, cached
        cache.put("k", "NEW");                             // writes SoR + invalidates the cached copy
        assertThat(sor).containsEntry("k", "NEW");
        assertThat(cache.get("k")).contains("NEW");        // load #2 -> fresh value, no stale read
        assertThat(loads.get()).isEqualTo(2);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
