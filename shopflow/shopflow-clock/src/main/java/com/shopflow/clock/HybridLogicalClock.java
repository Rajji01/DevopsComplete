package com.shopflow.clock;

import java.util.function.LongSupplier;

/**
 * Hybrid Logical Clock (Kulkarni, Demirbas et al., 2014) — the clock CockroachDB and YugabyteDB use. It
 * fuses the two clocks in this module's other classes: a timestamp that stays CLOSE TO WALL-CLOCK time
 * (unlike a {@link LamportClock}, whose counter means nothing in seconds) while still guaranteeing
 * causality (a→b ⇒ HLC(a) &lt; HLC(b)) even when the physical clocks are skewed. One total order, bounded
 * drift from NTP, and no per-node vector to carry around.
 *
 * <h3>How</h3>
 * State is a pair {@code (l, c)}: {@code l} tracks the largest physical time seen (its own or any peer's),
 * {@code c} is a small counter that breaks ties when events share an {@code l}. On a local/send event,
 * {@code l = max(l, now)}; the counter increments only if physical time didn't advance {@code l}, else
 * resets to 0 — so {@code l} never runs behind the wall clock, and {@code c} only grows during a burst
 * within the same millisecond. On receive, {@code l} jumps to the max of local {@code l}, the message's
 * {@code l}, and {@code now}, and {@code c} is set so the result strictly dominates both the local state and
 * the message — which is what carries causality across nodes despite clock skew.
 *
 * <h3>Why not just wall-clock time?</h3>
 * Physical clocks drift and jump (NTP steps), so two causally-ordered events on different machines can get
 * out-of-order wall-clock stamps — a last-writer-wins store would then pick the wrong winner. HLC keeps the
 * timestamp within the clock skew of real time (so it's human-meaningful and good for "roughly when") AND
 * monotonic with causality (so it's safe for ordering/LWW). Real systems pack {@code (l, c)} into one 64-bit
 * int (e.g. 48 bits ms + 16 bits counter); here they're kept as a readable pair.
 *
 * <p>Not thread-safe: one owner per clock, or guard externally.
 */
public final class HybridLogicalClock {

    /** An HLC timestamp: physical-ish {@code logical} time plus a tie-breaking {@code counter}. Total order. */
    public record Timestamp(long logical, int counter) implements Comparable<Timestamp> {
        @Override
        public int compareTo(Timestamp o) {
            int byLogical = Long.compare(logical, o.logical);
            return byLogical != 0 ? byLogical : Integer.compare(counter, o.counter);
        }
    }

    private final LongSupplier physicalClock;
    private long l;
    private int c;

    public HybridLogicalClock() {
        this(System::currentTimeMillis);
    }

    /** Inject the physical clock (ms) — for deterministic tests, or a custom time source. */
    public HybridLogicalClock(LongSupplier physicalClock) {
        this.physicalClock = physicalClock;
    }

    /** A local or send event: advance and return the new timestamp (stamp it on an outgoing message). */
    public Timestamp now() {
        long previous = l;
        l = Math.max(previous, physicalClock.getAsLong());
        if (l == previous) {
            c++;                // physical time didn't move the clock forward -> bump the tie-breaker
        } else {
            c = 0;              // physical time advanced -> fresh counter
        }
        return new Timestamp(l, c);
    }

    /** A receive event: merge the message's timestamp so the result strictly dominates both sides. */
    public Timestamp update(Timestamp message) {
        long previous = l;
        long now = physicalClock.getAsLong();
        l = Math.max(Math.max(previous, message.logical()), now);
        if (l == previous && l == message.logical()) {
            c = Math.max(c, message.counter()) + 1;   // both sides at this l -> beat the larger counter
        } else if (l == previous) {
            c = c + 1;                                 // local l dominates
        } else if (l == message.logical()) {
            c = message.counter() + 1;                 // message's l dominates
        } else {
            c = 0;                                     // physical time dominates both
        }
        return new Timestamp(l, c);
    }

    /** The current timestamp without advancing (a peek). */
    public Timestamp current() {
        return new Timestamp(l, c);
    }
}
