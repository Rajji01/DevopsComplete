package com.shopflow.balance.lb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Least-connections: send the next request to the backend with the fewest in-flight requests. Unlike
 * round-robin it reacts to real load — a slow backend accumulates connections and stops being picked. You
 * MUST {@code release} when a request finishes, or its count only ever grows and it's never chosen again.
 *
 * Cost: it needs an accurate global view of in-flight counts, which is easy in one process but hard across
 * many LB instances — that is exactly the problem power-of-two-choices sidesteps.
 */
public final class LeastConnectionsBalancer<N> {

    private final List<N> nodes;
    private final Map<N, Integer> active = new HashMap<>();

    public LeastConnectionsBalancer(List<N> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("need at least one node");
        }
        this.nodes = List.copyOf(nodes);
        for (N n : this.nodes) {
            active.put(n, 0);
        }
    }

    public synchronized N acquire() {
        N best = null;
        int min = Integer.MAX_VALUE;
        for (N n : nodes) {
            int a = active.get(n);
            if (a < min) {
                min = a;
                best = n;
            }
        }
        active.merge(best, 1, Integer::sum);
        return best;
    }

    public synchronized void release(N node) {
        active.computeIfPresent(node, (k, v) -> Math.max(0, v - 1));
    }

    public synchronized int active(N node) {
        return active.getOrDefault(node, 0);
    }
}
