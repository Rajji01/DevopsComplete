package com.shopflow.crdt;

import java.util.HashMap;
import java.util.Map;

/**
 * Grow-only counter (G-Counter): a state-based CRDT (CvRDT). Each replica owns ONE slot in a per-replica
 * map and only ever adds to its OWN slot; the counter's value is the sum of all slots. Two replicas converge
 * by taking the per-slot MAXIMUM — because each slot is monotonically increasing on exactly one replica, the
 * max is the most up-to-date value that slot has reached anywhere, and summing the merged slots gives the
 * total increments across the whole system.
 *
 * <h3>Why the merge is safe without coordination</h3>
 * {@link #mergeFrom} (per-slot max) is COMMUTATIVE, ASSOCIATIVE and IDEMPOTENT — the three properties that
 * make a state-based CRDT converge no matter the order, duplication or delay of the gossip. Replicas can
 * increment independently, exchange state in any order, re-merge the same state twice, and still agree. The
 * cost: it only grows (no decrement — that is what {@link PNCounter} adds), and it keeps one entry per replica.
 *
 * <p>Classic uses: a like/view counter replicated across datacentres, a quota counter, "how many times did
 * ANY node do X" — anywhere you want a counter that tolerates partitions and never needs a lock.
 *
 * <p>Not thread-safe: one writer per replica instance, or guard externally.
 */
public final class GCounter {

    private final Map<String, Long> slots = new HashMap<>();

    /** Add 1 to this replica's own slot. */
    public void increment(String replicaId) {
        increment(replicaId, 1);
    }

    /** Add {@code amount} (>= 0 — the counter only grows) to this replica's own slot. */
    public void increment(String replicaId, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("G-Counter only grows; use a PN-Counter to decrement");
        }
        slots.merge(replicaId, amount, Long::sum);
    }

    /** The counter value: the sum of every replica's slot. */
    public long value() {
        long total = 0;
        for (long slot : slots.values()) {
            total += slot;
        }
        return total;
    }

    /** Converge with another replica's state: each slot becomes the max of the two. Idempotent + commutative. */
    public void mergeFrom(GCounter other) {
        other.slots.forEach((replicaId, count) -> slots.merge(replicaId, count, Math::max));
    }
}
