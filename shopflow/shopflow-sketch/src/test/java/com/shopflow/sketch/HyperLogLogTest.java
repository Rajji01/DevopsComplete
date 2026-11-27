package com.shopflow.sketch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HyperLogLogTest {

    @Test
    void estimatesALargeCardinalityWithinTheStandardError() {
        var hll = HyperLogLog.create(14); // 16384 registers, ~0.8% standard error
        int distinct = 100_000;
        for (int i = 0; i < distinct; i++) {
            hll.add("item-" + i);
        }
        long estimate = hll.cardinality();
        // allow 3x the 1-sigma error as a stable bound (so the test isn't flaky), i.e. ~2.4%
        double tolerance = 3 * hll.standardError();
        assertThat(estimate).isCloseTo(distinct, org.assertj.core.data.Percentage.withPercentage(tolerance * 100));
    }

    @Test
    void addingTheSameItemAgainDoesNotChangeTheEstimate() {
        var hll = HyperLogLog.create(12);
        for (int i = 0; i < 1000; i++) {
            hll.add("stable-" + i);
        }
        long before = hll.cardinality();
        for (int repeat = 0; repeat < 5; repeat++) {
            for (int i = 0; i < 1000; i++) {
                hll.add("stable-" + i); // re-adding is idempotent: register keeps the MAX ρ
            }
        }
        assertThat(hll.cardinality()).isEqualTo(before);
    }

    @Test
    void isExactlyZeroForAnEmptySketch() {
        assertThat(HyperLogLog.create(14).cardinality()).isZero();
    }

    @Test
    void linearCountingIsAccurateForSmallCardinalities() {
        var hll = HyperLogLog.create(14);
        int distinct = 50; // tiny vs m=16384 -> exercises the linear-counting small-range path
        for (int i = 0; i < distinct; i++) {
            hll.add("small-" + i);
        }
        // small-range estimates are very tight; within 10% is comfortable
        assertThat(hll.cardinality()).isCloseTo(distinct, org.assertj.core.data.Percentage.withPercentage(10));
    }

    @Test
    void mergeEstimatesTheUnionOfTwoDisjointStreams() {
        var shardA = HyperLogLog.create(14);
        var shardB = HyperLogLog.create(14);
        for (int i = 0; i < 40_000; i++) {
            shardA.add("a-" + i);
        }
        for (int i = 0; i < 60_000; i++) {
            shardB.add("b-" + i); // disjoint key space from A
        }
        shardA.mergeFrom(shardB);
        // union of two disjoint sets of 40k and 60k = 100k distinct
        assertThat(shardA.cardinality()).isCloseTo(100_000L, org.assertj.core.data.Percentage.withPercentage(3));
    }

    @Test
    void mergeOfOverlappingStreamsCountsSharedItemsOnce() {
        var a = HyperLogLog.create(14);
        var b = HyperLogLog.create(14);
        for (int i = 0; i < 50_000; i++) {
            a.add("shared-" + i);
        }
        for (int i = 25_000; i < 75_000; i++) {
            b.add("shared-" + i); // 25k overlap with A, 25k new -> union is 75k
        }
        a.mergeFrom(b);
        assertThat(a.cardinality()).isCloseTo(75_000L, org.assertj.core.data.Percentage.withPercentage(3));
    }

    @Test
    void rejectsAnOutOfRangePrecision() {
        assertThatThrownBy(() -> HyperLogLog.create(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HyperLogLog.create(17)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToMergeDifferentPrecisions() {
        var small = HyperLogLog.create(10);
        var large = HyperLogLog.create(14);
        assertThatThrownBy(() -> small.mergeFrom(large)).isInstanceOf(IllegalArgumentException.class);
    }
}
