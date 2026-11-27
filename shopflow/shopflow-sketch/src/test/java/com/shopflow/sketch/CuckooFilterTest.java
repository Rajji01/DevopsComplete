package com.shopflow.sketch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.Test;

class CuckooFilterTest {

    @Test
    void hasNoFalseNegatives() {
        var cf = CuckooFilter.create(10_000, new Random(1));
        for (int i = 0; i < 5_000; i++) {
            assertThat(cf.add("item-" + i)).isTrue();
        }
        for (int i = 0; i < 5_000; i++) {
            assertThat(cf.mightContain("item-" + i)).isTrue(); // everything inserted is still found
        }
    }

    @Test
    void falsePositiveRateIsLow() {
        var cf = CuckooFilter.create(10_000, new Random(2));
        for (int i = 0; i < 5_000; i++) {
            cf.add("present-" + i);
        }
        int falsePositives = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            if (cf.mightContain("absent-" + i)) {
                falsePositives++;
            }
        }
        // 8-bit fingerprints, bucket size 4 -> well under 5% in practice
        assertThat((double) falsePositives / trials).isLessThan(0.05);
    }

    @Test
    void removeMakesAnItemAbsent() {
        var cf = CuckooFilter.create(1_000, new Random(3));
        cf.add("x");
        assertThat(cf.mightContain("x")).isTrue();
        assertThat(cf.remove("x")).isTrue();
        assertThat(cf.mightContain("x")).isFalse();
        assertThat(cf.remove("x")).isFalse(); // nothing left to remove
    }

    @Test
    void removingOneCopyLeavesTheOther() {
        var cf = CuckooFilter.create(1_000, new Random(4));
        cf.add("dup");
        cf.add("dup");               // same fingerprint stored twice
        assertThat(cf.remove("dup")).isTrue();
        assertThat(cf.mightContain("dup")).isTrue(); // one copy remains
    }

    @Test
    void sizeTracksAddsAndRemoves() {
        var cf = CuckooFilter.create(1_000, new Random(5));
        cf.add("a");
        cf.add("b");
        cf.add("c");
        assertThat(cf.size()).isEqualTo(3);
        cf.remove("b");
        assertThat(cf.size()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> CuckooFilter.create(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
