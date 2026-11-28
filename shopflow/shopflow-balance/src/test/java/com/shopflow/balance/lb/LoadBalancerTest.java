package com.shopflow.balance.lb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LoadBalancerTest {

    @Test
    void roundRobin_cyclesInOrder() {
        var rr = new RoundRobinBalancer<>(List.of("A", "B", "C"));
        assertThat(List.of(rr.next(), rr.next(), rr.next(), rr.next(), rr.next(), rr.next()))
                .containsExactly("A", "B", "C", "A", "B", "C");
    }

    @Test
    void weightedRoundRobin_picksProportionalToWeight() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("A", 3);
        weights.put("B", 1);
        var wrr = new WeightedRoundRobinBalancer<>(weights);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 8; i++) {           // two full cycles of total weight 4
            counts.merge(wrr.next(), 1, Integer::sum);
        }
        assertThat(counts.get("A")).isEqualTo(6);
        assertThat(counts.get("B")).isEqualTo(2);
    }

    @Test
    void leastConnections_prefersTheLeastLoadedAndReactsToRelease() {
        var lc = new LeastConnectionsBalancer<>(List.of("A", "B"));
        assertThat(lc.acquire()).isEqualTo("A");   // tie -> first
        assertThat(lc.acquire()).isEqualTo("B");   // A now has 1, B has 0
        lc.release("A");                            // A back to 0
        assertThat(lc.acquire()).isEqualTo("A");   // least loaded again
    }

    @Test
    void powerOfTwoChoices_keepsThePeakLoadCloseToAverage() {
        var nodes = List.of("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9");
        var p2c = new PowerOfTwoChoicesBalancer<>(nodes, new Random(7));
        for (int i = 0; i < 1000; i++) {
            p2c.acquire();                          // no releases -> loads accumulate
        }
        int max = 0;
        int sum = 0;
        for (String n : nodes) {
            max = Math.max(max, p2c.active(n));
            sum += p2c.active(n);
        }
        assertThat(sum).isEqualTo(1000);
        assertThat(max).isLessThanOrEqualTo(200);   // avg 100; P2C keeps the peak far below pure random's
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void peakEwma_snapsUpInstantlyOnASpikeButDecaysDownSlowly() {
        long[] clock = {0};                                     // mutable virtual clock (nanos)
        var b = new PeakEwmaBalancer<>(
                List.of("x"), TimeUnit.SECONDS.toNanos(10), ms(50), new Random(1), () -> clock[0]);

        // fresh node sits at its default-RTT baseline (so it isn't treated as free)
        assertThat(b.currentCost("x")).isCloseTo((double) ms(50), within((double) ms(1)));

        // a 500ms sample is HIGHER than the estimate -> snap straight up to it, no smoothing
        b.complete("x", ms(500));
        assertThat(b.currentCost("x")).isCloseTo((double) ms(500), within((double) ms(1)));

        // 10s later (one tau), a fast 50ms sample is LOWER -> blended in, not snapped:
        // w = exp(-1) = 0.368; cost = 500*0.368 + 50*0.632 ≈ 216ms
        clock[0] += TimeUnit.SECONDS.toNanos(10);
        b.complete("x", ms(50));
        assertThat(b.currentCost("x")).isCloseTo((double) ms(216), within((double) ms(5)));
    }

    @Test
    void peakEwma_steersAwayFromTheSlowBackend() {
        long[] clock = {0};
        var b = new PeakEwmaBalancer<>(
                List.of("fast", "slow"), TimeUnit.SECONDS.toNanos(10), ms(100), new Random(42), () -> clock[0]);
        b.complete("slow", ms(1000));                            // mark slow as ~10x costlier than fast

        int fast = 0;
        for (int i = 0; i < 1000; i++) {
            String n = b.acquire();
            if (n.equals("fast")) {
                fast++;
            }
            b.complete(n, n.equals("fast") ? ms(100) : ms(1000)); // keep each backend's latency profile
        }
        // P2C over 2 nodes: fast wins unless BOTH samples are slow (~1/4) -> ~75% to fast, hotspot avoided
        assertThat(fast).isBetween(700, 800);
    }

    @Test
    void peakEwma_rejectsBadArgs() {
        assertThatThrownBy(() -> new PeakEwmaBalancer<>(List.of(), 1L, 1L, new Random(), () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PeakEwmaBalancer<>(List.of("a"), 0L, 1L, new Random(), () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
