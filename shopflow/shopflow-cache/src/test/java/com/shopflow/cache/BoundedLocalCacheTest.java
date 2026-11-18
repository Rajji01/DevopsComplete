package com.shopflow.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import com.shopflow.cache.eviction.LruEvictionPolicy;

/** The store: capacity eviction, TTL expiry (driven by a fake clock, no sleeping), and stats. */
class BoundedLocalCacheTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;

    @Test
    void evictsWhenOverCapacity() {
        var c = new BoundedLocalCache<String, String>(2, new LruEvictionPolicy<>(), clock, 0);
        c.put("a", "A", 0);
        c.put("b", "B", 0);
        assertThat(c.getIfPresent("a")).contains("A");   // touch a -> b becomes the LRU victim
        c.put("c", "C", 0);                               // over capacity -> evict b
        assertThat(c.getIfPresent("b")).isEmpty();
        assertThat(c.getIfPresent("a")).contains("A");
        assertThat(c.getIfPresent("c")).contains("C");
        assertThat(c.stats().evictions()).isEqualTo(1);
    }

    @Test
    void entriesExpireByTtl() {
        var c = new BoundedLocalCache<String, String>(10, new LruEvictionPolicy<>(), clock, 0);
        c.put("k", "V", 1000);
        assertThat(c.getIfPresent("k")).contains("V");
        now.set(999);
        assertThat(c.getIfPresent("k")).contains("V");
        now.set(1000);
        assertThat(c.getIfPresent("k")).isEmpty();        // TTL elapsed
    }

    @Test
    void statsTrackHitsMissesAndRate() {
        var c = new BoundedLocalCache<String, String>(10, new LruEvictionPolicy<>(), clock, 0);
        c.put("k", "V", 0);
        c.getIfPresent("k");          // hit
        c.getIfPresent("absent");     // miss
        assertThat(c.stats().hits()).isEqualTo(1);
        assertThat(c.stats().misses()).isEqualTo(1);
        assertThat(c.stats().hitRate()).isEqualTo(0.5);
    }
}
