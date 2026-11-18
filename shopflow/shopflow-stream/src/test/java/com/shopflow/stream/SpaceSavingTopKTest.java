package com.shopflow.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SpaceSavingTopKTest {

    @Test
    void surfacesTheHeaviestHittersAboveTheOneOffNoise() {
        var topk = new SpaceSavingTopK<String>(20);
        for (int i = 0; i < 1000; i++) {
            topk.offer("a");
        }
        for (int i = 0; i < 500; i++) {
            topk.offer("b");
        }
        for (int i = 0; i < 200; i++) {
            topk.offer("c");
        }
        for (int i = 0; i < 100; i++) {
            topk.offer("noise-" + i); // 100 distinct, once each
        }
        List<SpaceSavingTopK.Entry<String>> top = topk.top();
        assertThat(top.get(0).item()).isEqualTo("a");
        assertThat(top.get(1).item()).isEqualTo("b");
        assertThat(top.get(2).item()).isEqualTo("c");
        assertThat(top.get(0).count()).isGreaterThanOrEqualTo(1000);
        assertThat(top.get(0).error()).isZero(); // "a" was tracked from the start, inherited nothing
    }
}
