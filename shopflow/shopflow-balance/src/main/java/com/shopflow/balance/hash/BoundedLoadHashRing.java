package com.shopflow.balance.hash;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent hashing with BOUNDED LOADS (Google, 2016 — "Consistent Hashing with Bounded Loads", the
 * algorithm behind HAProxy's and Vimeo's balancers). Plain {@link HashRing} keeps key movement minimal when
 * nodes change, but says nothing about FAIRNESS: a popular key range can pile onto one node while its
 * neighbour idles. This adds a hard cap — no node holds more than {@code (1+ε)·average} keys — while keeping
 * consistent hashing's "only ~1/N keys move on a change" property.
 *
 * <h3>How</h3>
 * A key is placed like normal consistent hashing — first node clockwise from its hash — EXCEPT that if that
 * node is already at capacity {@code cap = ceil((1+ε)·total / nodes)}, the search continues clockwise to the
 * next node with spare room. Because the caps sum to at least the total number of keys, a slot always exists.
 * Smaller ε = tighter balance but more keys bumped off their first choice; ε around 0.25 is typical.
 *
 * <p>Stateful by design (unlike the stateless {@link HashRing#route}): it tracks each key's assignment so it
 * can enforce the cap and {@link #release} a key's slot later — the model for routing live
 * requests/connections to backends, not just hashing a key to a shard. Not thread-safe; guard externally.
 *
 * @param <N> node type (used as a map key and via {@code toString()} for ring placement)
 */
public final class BoundedLoadHashRing<N> {

    private final int virtualNodes;
    private final double epsilon;
    private final NavigableMap<Long, N> ring = new TreeMap<>();
    private final Set<N> nodes = new LinkedHashSet<>();
    private final Map<N, Integer> load = new HashMap<>();
    private final Map<String, N> assignment = new HashMap<>();

    public BoundedLoadHashRing(int virtualNodes, double epsilon) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be positive");
        }
        if (epsilon < 0) {
            throw new IllegalArgumentException("epsilon must be >= 0");
        }
        this.virtualNodes = virtualNodes;
        this.epsilon = epsilon;
    }

    public void addNode(N node) {
        if (!nodes.add(node)) {
            return;
        }
        load.putIfAbsent(node, 0);
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(Hashing.hash64(node.toString() + "#" + i), node);
        }
    }

    /**
     * Assign {@code key} to a node, honouring the per-node cap, and remember it. Idempotent: a key already
     * assigned returns its current node (no double-counting). Throws if there are no nodes.
     */
    public N assign(String key) {
        N existing = assignment.get(key);
        if (existing != null) {
            return existing;
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("no nodes to assign to");
        }
        int total = assignment.size();
        int cap = (int) Math.ceil((1.0 + epsilon) * (total + 1) / nodes.size()); // +1: this key counts in the average

        for (N node : clockwiseFrom(Hashing.hash64(key))) {
            if (load.get(node) < cap) {
                assignment.put(key, node);
                load.merge(node, 1, Integer::sum);
                return node;
            }
        }
        // unreachable: sum of caps (>= nodes * cap) always covers total+1 keys
        throw new IllegalStateException("no node under capacity — invariant violated");
    }

    /** Free {@code key}'s slot (e.g. a connection closed), so its node regains capacity. */
    public void release(String key) {
        N node = assignment.remove(key);
        if (node != null) {
            load.merge(node, -1, Integer::sum);
        }
    }

    /** Current number of keys assigned to {@code node}. */
    public int load(N node) {
        return load.getOrDefault(node, 0);
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** Distinct physical nodes in clockwise order starting at {@code h}, wrapping past the top of the ring. */
    private Set<N> clockwiseFrom(long h) {
        Set<N> order = new LinkedHashSet<>();
        ring.tailMap(h, true).values().forEach(order::add);
        ring.headMap(h, false).values().forEach(order::add); // wrap around
        return order;
    }
}
