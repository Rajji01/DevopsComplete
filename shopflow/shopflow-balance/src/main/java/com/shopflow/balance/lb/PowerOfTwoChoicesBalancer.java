package com.shopflow.balance.lb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Power-of-two-choices (P2C): pick TWO backends at random, send to the less loaded of the two. The famous
 * result ("The Power of Two Random Choices") is that this one extra sample cuts the maximum load from
 * ~O(log n / log log n) above average (pure random) down to ~O(log log n) — almost perfectly balanced —
 * WITHOUT needing a global least-connections view. Each LB instance only compares two local samples, so it
 * scales to many balancers where true least-connections can't. The randomness is injected for determinism.
 */
public final class PowerOfTwoChoicesBalancer<N> {

    private final List<N> nodes;
    private final Map<N, Integer> active = new HashMap<>();
    private final Random random;

    public PowerOfTwoChoicesBalancer(List<N> nodes, Random random) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("need at least one node");
        }
        this.nodes = List.copyOf(nodes);
        this.random = random;
        for (N n : this.nodes) {
            active.put(n, 0);
        }
    }

    public synchronized N acquire() {
        N a = nodes.get(random.nextInt(nodes.size()));
        N b = nodes.get(random.nextInt(nodes.size()));
        N chosen = active.get(a) <= active.get(b) ? a : b; // the lighter of the two samples
        active.merge(chosen, 1, Integer::sum);
        return chosen;
    }

    public synchronized void release(N node) {
        active.computeIfPresent(node, (k, v) -> Math.max(0, v - 1));
    }

    public synchronized int active(N node) {
        return active.getOrDefault(node, 0);
    }
}
