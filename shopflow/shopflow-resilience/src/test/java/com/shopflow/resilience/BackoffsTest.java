package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

class BackoffsTest {

    @Test
    void fixedIsConstant() {
        var s = Backoffs.fixed(100);
        assertThat(s.delayMillis(1)).isEqualTo(100);
        assertThat(s.delayMillis(7)).isEqualTo(100);
    }

    @Test
    void exponentialDoublesAndCaps() {
        var s = Backoffs.exponential(100, 1000);
        assertThat(s.delayMillis(1)).isEqualTo(100);
        assertThat(s.delayMillis(2)).isEqualTo(200);
        assertThat(s.delayMillis(3)).isEqualTo(400);
        assertThat(s.delayMillis(4)).isEqualTo(800);
        assertThat(s.delayMillis(5)).isEqualTo(1000);   // capped
        assertThat(s.delayMillis(20)).isEqualTo(1000);  // stays capped, no overflow
    }

    @Test
    void fullJitterStaysInZeroToExp() {
        var s = Backoffs.fullJitter(100, 1000, new Random(1));
        for (int a = 1; a <= 8; a++) {
            long exp = Math.min(1000, 100L << Math.min(a - 1, 30));
            assertThat(s.delayMillis(a)).isBetween(0L, exp);
        }
    }

    @Test
    void equalJitterStaysInHalfExpToExp() {
        var s = Backoffs.equalJitter(100, 1000, new Random(1));
        for (int a = 1; a <= 8; a++) {
            long exp = Math.min(1000, 100L << Math.min(a - 1, 30));
            assertThat(s.delayMillis(a)).isBetween(exp / 2, exp);
        }
    }
}
