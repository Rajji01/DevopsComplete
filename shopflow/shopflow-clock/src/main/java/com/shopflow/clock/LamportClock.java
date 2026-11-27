package com.shopflow.clock;

/**
 * Lamport logical clock: a single monotonically-increasing counter that orders events across machines
 * WITHOUT synchronised wall-clocks. The rule is tiny — a local event ticks the counter, and receiving a
 * message sets the counter to {@code max(local, received) + 1}. That guarantees the one property Lamport
 * set out to get: if event a *happened-before* event b (a→b, same process or a message from a to b), then
 * {@code L(a) < L(b)}.
 *
 * <h3>The honest limit (and why vector clocks exist)</h3>
 * The converse is FALSE: {@code L(a) < L(b)} does NOT mean a→b — a and b might be concurrent and just
 * happened to get different numbers. A Lamport clock gives a total order consistent with causality (great
 * for tie-breaking, e.g. "lowest timestamp, then lowest node id, wins"), but it cannot DETECT concurrency.
 * When you need to know "did these two events actually causally relate, or were they concurrent?" you need a
 * {@link VectorClock}.
 *
 * <p>Not thread-safe: one owner per clock, or guard externally.
 */
public final class LamportClock {

    private long time;

    /** A local event happened: advance and return the new timestamp. */
    public long tick() {
        return ++time;
    }

    /** Stamp an outgoing message with the current time (a send is a local event). */
    public long sendTimestamp() {
        return tick();
    }

    /** A message arrived carrying {@code receivedTimestamp}: advance past both, return the new time. */
    public long onReceive(long receivedTimestamp) {
        time = Math.max(time, receivedTimestamp) + 1;
        return time;
    }

    /** The current logical time. */
    public long time() {
        return time;
    }
}
