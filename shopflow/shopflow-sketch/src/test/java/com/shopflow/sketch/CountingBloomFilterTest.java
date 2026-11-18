package com.shopflow.sketch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountingBloomFilterTest {

    @Test
    void removeMakesAnItemAbsent() {
        CountingBloomFilter<String> cbf = CountingBloomFilter.create(1000, 0.01);
        cbf.add("x");
        assertThat(cbf.mightContain("x")).isTrue();
        cbf.remove("x");
        assertThat(cbf.mightContain("x")).isFalse(); // counters back to zero
    }

    @Test
    void aDoubleAddSurvivesASingleRemove() {
        CountingBloomFilter<String> cbf = CountingBloomFilter.create(1000, 0.01);
        cbf.add("x");
        cbf.add("x");
        cbf.remove("x");
        assertThat(cbf.mightContain("x")).isTrue(); // still counted once
    }

    @Test
    void neverGivesAFalseNegativeForAddedItems() {
        CountingBloomFilter<String> cbf = CountingBloomFilter.create(1000, 0.01);
        for (int i = 0; i < 500; i++) {
            cbf.add("k-" + i);
        }
        for (int i = 0; i < 500; i++) {
            assertThat(cbf.mightContain("k-" + i)).isTrue();
        }
    }
}
