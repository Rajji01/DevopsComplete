package com.shopflow.clock;

import java.util.HashMap;
import java.util.Map;

/**
 * Vector clock: one counter PER node, so it captures causality exactly — not just "a is ordered before b"
 * (which a {@link LamportClock} gives) but whether a→b, b→a, or a and b are genuinely CONCURRENT. Each node
 * bumps its own component on a local event; on receiving a message it merges (element-wise max) the sender's
 * vector and then bumps its own. Comparing two vectors then tells you their causal relationship:
 *
 * <ul>
 *   <li>{@code BEFORE}  — every component of this ≤ other, and at least one strictly &lt;  (this → other)</li>
 *   <li>{@code AFTER}   — the mirror image (other → this)</li>
 *   <li>{@code EQUAL}   — identical vectors</li>
 *   <li>{@code CONCURRENT} — each has a component the other doesn't dominate; neither happened-before the
 *       other (this is the case a Lamport clock cannot detect, and the one that signals a conflict to
 *       resolve — e.g. an OR-Set add-vs-remove or a Dynamo-style sibling)</li>
 * </ul>
 *
 * <p>Missing node components read as 0. Not thread-safe: one owner per clock, or guard externally.
 */
public final class VectorClock {

    public enum Ordering { BEFORE, AFTER, EQUAL, CONCURRENT }

    private final Map<String, Long> components = new HashMap<>();

    /** A local event on {@code node}: bump that node's own component. */
    public void increment(String node) {
        components.merge(node, 1L, Long::sum);
    }

    /** This node's current count (0 if never seen). */
    public long get(String node) {
        return components.getOrDefault(node, 0L);
    }

    /**
     * Receive another vector: take the element-wise MAX so this clock now reflects everything the sender had
     * seen. (By convention the receiver then calls {@link #increment} for its own receive event.)
     */
    public void mergeFrom(VectorClock other) {
        other.components.forEach((node, count) -> components.merge(node, count, Math::max));
    }

    /** The causal relationship between this vector and {@code other}. */
    public Ordering compareTo(VectorClock other) {
        boolean thisLess = false;    // some component where this < other
        boolean thisGreater = false; // some component where this > other
        // consider every node either side knows about
        var allNodes = new java.util.HashSet<String>(components.keySet());
        allNodes.addAll(other.components.keySet());
        for (String node : allNodes) {
            long a = get(node);
            long b = other.get(node);
            if (a < b) {
                thisLess = true;
            } else if (a > b) {
                thisGreater = true;
            }
        }
        if (thisLess && thisGreater) {
            return Ordering.CONCURRENT;
        }
        if (thisLess) {
            return Ordering.BEFORE;
        }
        if (thisGreater) {
            return Ordering.AFTER;
        }
        return Ordering.EQUAL;
    }

    /** True iff this → other (this causally happened-before other). */
    public boolean happenedBefore(VectorClock other) {
        return compareTo(other) == Ordering.BEFORE;
    }

    /** True iff neither happened-before the other — a genuine conflict to resolve. */
    public boolean isConcurrentWith(VectorClock other) {
        return compareTo(other) == Ordering.CONCURRENT;
    }

    @Override
    public String toString() {
        return new java.util.TreeMap<>(components).toString();
    }
}
