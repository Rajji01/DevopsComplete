package com.shopflow.balance.lb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
}
