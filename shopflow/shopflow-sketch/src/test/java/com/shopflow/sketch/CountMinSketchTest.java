package com.shopflow.sketch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CountMinSketchTest {

    @Test
    void neverUnderestimates() {
        var cms = CountMinSketch.create(0.001, 0.01);
        var truth = new HashMap<String, Long>();
        String[] keys = {"a", "b", "c", "d"};
        var r = new Random(3);
        for (int i = 0; i < 5000; i++) {
            String k = keys[r.nextInt(keys.length)];
            cms.add(k, 1);
            truth.merge(k, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : truth.entrySet()) {
            assertThat(cms.estimate(e.getKey())).isGreaterThanOrEqualTo(e.getValue()); // CMS only over-counts
        }
    }

    @Test
    void estimatesAHeavyHitterTightly() {
        var cms = CountMinSketch.create(0.001, 0.01);
        cms.add("hot", 1000);
        for (int i = 0; i < 200; i++) {
            cms.add("noise-" + i, 1); // light background keys
        }
        long est = cms.estimate("hot");
        assertThat(est).isGreaterThanOrEqualTo(1000);  // never under
        assertThat(est).isLessThanOrEqualTo(1050);      // over-estimate bounded by epsilon * total
    }
}
