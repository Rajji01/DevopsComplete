package com.shopflow.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

class ReservoirSamplerTest {

    @Test
    void theSampleNeverExceedsCapacity() {
        var small = new ReservoirSampler<Integer>(5, new Random(1));
        for (int i = 0; i < 3; i++) {
            small.offer(i);
        }
        assertThat(small.sample()).hasSize(3);        // stream shorter than k
        for (int i = 3; i < 100; i++) {
            small.offer(i);
        }
        assertThat(small.sample()).hasSize(5);        // capped at k
    }

    @Test
    void everyElementIsEquallyLikelyToBeSampled() {
        int[] chosen = new int[10];
        var rnd = new Random(42);
        for (int trial = 0; trial < 10000; trial++) {
            var r = new ReservoirSampler<Integer>(1, rnd); // pick 1 of 10
            for (int i = 0; i < 10; i++) {
                r.offer(i);
            }
            chosen[r.sample().get(0)]++;
        }
        for (int c : chosen) {
            assertThat(c).isBetween(800, 1200);        // uniform -> each ~1000 of 10000 (±20%, far inside 6σ)
        }
    }
}
