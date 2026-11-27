package com.shopflow.crdt;

/**
 * Positive-Negative counter (PN-Counter): a state-based CRDT that CAN decrement, built from two
 * {@link GCounter}s — one accumulating increments (P), one accumulating decrements (N). The value is
 * {@code P.value() − N.value()}. Both halves are grow-only, so each is a well-behaved CvRDT, and merging a
 * PN-Counter is just merging the two halves independently — which inherits the commutative / associative /
 * idempotent convergence from {@link GCounter}.
 *
 * <p>The trick is that you can't make a single counter both go up and down AND still merge by max (a max
 * merge would lose decrements). Splitting into two monotonic counters sidesteps that: a decrement is "an
 * increment on the N side", so the whole thing stays monotonic per slot and the max-merge is still valid.
 *
 * <p>Uses: an inventory/stock count or an account-style balance replicated across regions where you want
 * local reads/writes under a partition and eventual convergence — with the honest caveat that a CRDT counter
 * can go temporarily negative or oversell under concurrency, so it fits "soft" counts, not a
 * strongly-consistent ledger (that is what {@code ledger-service} with its DB + double-entry is for).
 *
 * <p>Not thread-safe: one writer per replica instance, or guard externally.
 */
public final class PNCounter {

    private final GCounter increments = new GCounter();
    private final GCounter decrements = new GCounter();

    /** Add 1 on this replica. */
    public void increment(String replicaId) {
        increments.increment(replicaId, 1);
    }

    /** Subtract 1 on this replica. */
    public void decrement(String replicaId) {
        decrements.increment(replicaId, 1);
    }

    /** Add {@code amount} (>= 0) on this replica. */
    public void increment(String replicaId, long amount) {
        increments.increment(replicaId, amount);
    }

    /** Subtract {@code amount} (>= 0) on this replica. */
    public void decrement(String replicaId, long amount) {
        decrements.increment(replicaId, amount);
    }

    /** The counter value: total increments minus total decrements. May be negative. */
    public long value() {
        return increments.value() - decrements.value();
    }

    /** Converge with another replica: merge the P half and the N half independently. */
    public void mergeFrom(PNCounter other) {
        increments.mergeFrom(other.increments);
        decrements.mergeFrom(other.decrements);
    }
}
