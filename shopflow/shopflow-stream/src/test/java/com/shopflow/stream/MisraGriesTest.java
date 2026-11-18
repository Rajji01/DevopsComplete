package com.shopflow.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MisraGriesTest {

    @Test
    void everyTrulyFrequentItemSurvivesAsACandidate() {
        var mg = new MisraGries<String>(10); // guarantees finding items with frequency > n/10
        for (int i = 0; i < 1000; i++) {
            mg.offer("a");
        }
        for (int i = 0; i < 500; i++) {
            mg.offer("b");
        }
        for (int i = 0; i < 100; i++) {
            mg.offer("noise-" + i);           // n = 1600, so n/10 = 160; a and b clear it, noise doesn't
        }
        Map<String, Long> candidates = mg.candidates();
        assertThat(candidates).containsKey("a");
        assertThat(candidates).containsKey("b");
    }
}
