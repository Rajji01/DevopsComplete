package com.shopflow.cache.eviction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Each policy's defining rule, pinned: who gets evicted after a specific access pattern. */
class EvictionPolicyTest {

    @Test
    void lru_evictsTheLeastRecentlyUsed() {
        var p = new LruEvictionPolicy<String>();
        p.recordInsert("a");
        p.recordInsert("b");
        p.recordInsert("c");
        p.recordAccess("a");                         // a is now the most recently used
        assertThat(p.evictionCandidate()).isEqualTo("b");
    }

    @Test
    void fifo_evictsTheOldestInsertedIgnoringUse() {
        var p = new FifoEvictionPolicy<String>();
        p.recordInsert("a");
        p.recordInsert("b");
        p.recordInsert("c");
        p.recordAccess("a");
        p.recordAccess("a");                         // ignored by FIFO
        assertThat(p.evictionCandidate()).isEqualTo("a");
    }

    @Test
    void lfu_evictsTheLeastFrequentlyUsed() {
        var p = new LfuEvictionPolicy<String>();
        p.recordInsert("a");
        p.recordInsert("b");
        p.recordInsert("c");                         // all at frequency 1
        p.recordAccess("a");
        p.recordAccess("a");                         // a = 3
        p.recordAccess("b");                         // b = 2, c = 1
        assertThat(p.evictionCandidate()).isEqualTo("c");
    }

    @Test
    void remove_takesAKeyOutOfConsideration() {
        var p = new LruEvictionPolicy<String>();
        p.recordInsert("a");
        p.recordInsert("b");
        p.remove("a");
        assertThat(p.evictionCandidate()).isEqualTo("b");
    }
}
