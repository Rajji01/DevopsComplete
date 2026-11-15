package com.shopflow.sketch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BloomFilterTest {

    @Test
    void neverGivesAFalseNegative() {
        BloomFilter<String> bf = BloomFilter.create(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            bf.add("item-" + i);
        }
        for (int i = 0; i < 1000; i++) {
            assertThat(bf.mightContain("item-" + i)).isTrue(); // everything added must report present
        }
    }

    @Test
    void falsePositiveRateIsRoughlyHonoured() {
        BloomFilter<String> bf = BloomFilter.create(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            bf.add("item-" + i);
        }
        int falsePositives = 0;
        for (int i = 0; i < 10000; i++) {
            if (bf.mightContain("absent-" + i)) {   // none of these were added
                falsePositives++;
            }
        }
        assertThat(falsePositives / 10000.0).isLessThan(0.05); // target 1%; loose bound avoids flakiness
    }
}
