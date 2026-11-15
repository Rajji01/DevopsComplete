package com.shopflow.balance.hash;

import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent hashing. Nodes and keys are hashed onto the same 64-bit ring; a key is served by the first
 * node clockwise from it. Add or remove a node and only the keys in that one arc move — about 1/N of them —
 * instead of the near-total reshuffle a plain {@code hash(key) % nodeCount} suffers when N changes. That's
 * the whole point: used for sharded caches/DBs so a scaling event doesn't cold-miss everything.
 *
 * VIRTUAL NODES: each physical node is placed at many ring positions (vnodes). Without them, 3 nodes make 3
 * arcs of wildly uneven size (poor balance) and removing one dumps its whole arc on a single neighbour. With
 * ~150 vnodes each, the arcs interleave, so load is even and a removed node's keys spread across ALL others.
 */
public final class HashRing<N> {

    private final int virtualNodes;
    private final NavigableMap<Long, N> ring = new TreeMap<>();
    private final Set<N> nodes = new HashSet<>();

    public HashRing(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be positive");
        }
        this.virtualNodes = virtualNodes;
    }

    public synchronized void add(N node) {
        if (!nodes.add(node)) {
            return;
        }
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(Hashing.hash64(node.toString() + "#" + i), node);
        }
    }

    public synchronized void remove(N node) {
        if (!nodes.remove(node)) {
            return;
        }
        for (int i = 0; i < virtualNodes; i++) {
            ring.remove(Hashing.hash64(node.toString() + "#" + i));
        }
    }

    /** The node that serves {@code key}: first position clockwise, wrapping past the top of the ring. */
    public synchronized N route(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        long h = Hashing.hash64(key);
        Map.Entry<Long, N> e = ring.ceilingEntry(h);
        return (e != null ? e : ring.firstEntry()).getValue();
    }

    public synchronized int nodeCount() {
        return nodes.size();
    }
}
