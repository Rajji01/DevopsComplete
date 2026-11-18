package com.shopflow.balance.hash;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Rendezvous (Highest Random Weight) hashing — the other way to get consistent-hashing's "minimal movement"
 * property, without a ring. For a key, hash (node, key) for every node and pick the node with the highest
 * value. Remove that node and only ITS keys move (each re-picks the next-highest node); the rest are
 * unaffected, because their winner didn't change.
 *
 * vs the ring: no virtual nodes to tune and naturally even, but routing is O(nodes) per lookup instead of
 * O(log positions). Great for small-to-medium node counts (shard/replica selection).
 */
public final class RendezvousHash<N> {

    private final Set<N> nodes = ConcurrentHashMap.newKeySet();

    public void add(N node) {
        nodes.add(node);
    }

    public void remove(N node) {
        nodes.remove(node);
    }

    public N route(String key) {
        N best = null;
        long bestScore = Long.MIN_VALUE;
        for (N node : nodes) {
            long score = Hashing.hash64(node.toString() + "|" + key);
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    public int nodeCount() {
        return nodes.size();
    }
}
