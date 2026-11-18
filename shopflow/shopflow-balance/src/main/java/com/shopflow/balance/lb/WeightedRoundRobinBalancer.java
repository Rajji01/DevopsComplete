package com.shopflow.balance.lb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Weighted round-robin for backends of different capacity (a 3 gets picked 3x as often as a 1). This is the
 * SMOOTH variant (nginx's algorithm): rather than emitting AAA...BBB...  in bursts, it interleaves them
 * (A A B A for weights {A:3,B:1}), which spreads load evenly instead of hammering the heavy node in a run.
 *
 * Each pick: bump every node's running "current" by its weight, take the highest, then subtract the total
 * weight from the winner. Over one full cycle (sum of weights) each node is chosen exactly its weight times.
 */
public final class WeightedRoundRobinBalancer<N> implements Balancer<N> {

    private static final class Weighted<N> {
        final N node;
        final int weight;
        int current;

        Weighted(N node, int weight) {
            this.node = node;
            this.weight = weight;
        }
    }

    private final List<Weighted<N>> items = new ArrayList<>();
    private final int total;

    public WeightedRoundRobinBalancer(Map<N, Integer> weights) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("need at least one node");
        }
        int sum = 0;
        for (Map.Entry<N, Integer> e : weights.entrySet()) {
            if (e.getValue() <= 0) {
                throw new IllegalArgumentException("weight must be positive for " + e.getKey());
            }
            items.add(new Weighted<>(e.getKey(), e.getValue()));
            sum += e.getValue();
        }
        this.total = sum;
    }

    @Override
    public synchronized N next() {
        Weighted<N> best = null;
        for (Weighted<N> w : items) {
            w.current += w.weight;
            if (best == null || w.current > best.current) {
                best = w;
            }
        }
        best.current -= total;
        return best.node;
    }
}
