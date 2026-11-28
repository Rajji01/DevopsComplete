package com.shopflow.balance.lb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Peak-EWMA load balancer (Finagle / Linkerd / Envoy "peak_ewma"). The upgrade over plain
 * {@link PowerOfTwoChoicesBalancer}: instead of scoring a backend by its in-flight COUNT, it scores by an
 * estimate of how costly it is right now:
 *
 * <pre>
 *   cost(node) = ewmaRtt(node) * (pending(node) + 1)
 * </pre>
 *
 * This reacts to both dimensions at once — a backend that is slow (high RTT) OR busy (high pending) looks
 * expensive — so a GC-paused or degraded node is steered around even if it isn't holding many connections.
 * P2C counting can't see that; two nodes with equal in-flight look identical to it even if one is 10x slower.
 *
 * <p><b>Why "peak".</b> The RTT estimate is an exponentially-weighted moving average, but asymmetric: a new
 * sample that is <i>higher</i> than the current estimate snaps the estimate up to it instantly (react to a
 * latency spike immediately), while a <i>lower</i> sample is blended in slowly and the estimate also decays
 * on its own over idle time. So the balancer is quick to flee a backend that just got slow and cautious to
 * trust one that just looked fast — exactly the bias you want when shedding away from a degrading node.
 *
 * <pre>
 *   on a completed request with round-trip time rtt, at time now:
 *     w = exp(-(now - lastUpdate) / decay)        // time-decay weight, 0..1
 *     cost = (rtt > cost) ? rtt                    // PEAK: jump up to a new high instantly
 *                         : cost*w + rtt*(1 - w)   // otherwise fade toward the lower sample
 * </pre>
 *
 * <p>Selection is still power-of-two-choices: sample two backends at random, take the one with the lower
 * current cost. One extra sample buys almost all the benefit of scoring every node, with none of the herding
 * you get from "everyone picks the single globally-cheapest node" (which just moves the hotspot around).
 *
 * <p>A freshly added backend (no samples yet) starts at a configurable default RTT so it isn't treated as
 * free and flooded before it has proven itself — Tower uses a 1s default for the same reason.
 *
 * <p>The clock is injected ({@link LongSupplier} of nanos) so tests are deterministic; in production pass
 * {@code System::nanoTime}. Not thread-safe per call is handled by {@code synchronized} on the mutating
 * methods, matching the other connection-aware balancers in this package.
 */
public final class PeakEwmaBalancer<N> {

    /** Per-backend live state: the EWMA RTT estimate, when it was last updated, and in-flight count. */
    private static final class Stat {
        double costNanos;   // current RTT estimate in nanos (the "cost")
        long lastUpdate;    // clock value at the last observe/decay, for the exp() weight
        int pending;        // in-flight requests acquired but not yet completed
    }

    private final List<N> nodes;
    private final Map<N, Stat> stats = new HashMap<>();
    private final double decayNanos;        // tau: larger = estimate fades more slowly
    private final Random random;
    private final LongSupplier clock;

    /**
     * @param nodes            backends to balance across (at least one)
     * @param decayNanos       decay time constant tau; e.g. {@code TimeUnit.SECONDS.toNanos(10)}
     * @param defaultRttNanos  starting cost for a backend with no samples yet (e.g. 1s), so it isn't flooded
     * @param random           source for the two-choices sampling (inject for determinism)
     * @param clock            nanosecond clock; {@code System::nanoTime} in production
     */
    public PeakEwmaBalancer(List<N> nodes, long decayNanos, long defaultRttNanos, Random random, LongSupplier clock) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("need at least one node");
        }
        if (decayNanos <= 0) {
            throw new IllegalArgumentException("decayNanos must be positive");
        }
        this.nodes = List.copyOf(nodes);
        this.decayNanos = decayNanos;
        this.random = random;
        this.clock = clock;
        long now = clock.getAsLong();
        for (N n : this.nodes) {
            Stat s = new Stat();
            s.costNanos = defaultRttNanos;
            s.lastUpdate = now;
            stats.put(n, s);
        }
    }

    /** Pick the cheaper of two random samples and register one in-flight request against it. */
    public synchronized N acquire() {
        long now = clock.getAsLong();
        N a = nodes.get(random.nextInt(nodes.size()));
        N b = nodes.get(random.nextInt(nodes.size()));
        N chosen = cost(stats.get(a), now) <= cost(stats.get(b), now) ? a : b;
        stats.get(chosen).pending++;
        return chosen;
    }

    /**
     * Report that a request on {@code node} finished with the given round-trip time. Decrements in-flight and
     * folds the sample into the peak-EWMA estimate.
     */
    public synchronized void complete(N node, long rttNanos) {
        Stat s = stats.get(node);
        if (s == null) {
            return;
        }
        if (s.pending > 0) {
            s.pending--;
        }
        long now = clock.getAsLong();
        double w = decayWeight(s, now);
        s.costNanos = rttNanos > s.costNanos ? rttNanos : s.costNanos * w + rttNanos * (1.0 - w);
        s.lastUpdate = now;
    }

    /** Current scoring cost for a backend: decayed RTT estimate times (pending + 1). Read-only. */
    public synchronized double currentCost(N node) {
        Stat s = stats.get(node);
        return s == null ? Double.POSITIVE_INFINITY : cost(s, clock.getAsLong());
    }

    public synchronized int pending(N node) {
        Stat s = stats.get(node);
        return s == null ? 0 : s.pending;
    }

    // --- internals ---------------------------------------------------------

    /** Read-only cost = decayed estimate * (pending + 1); does not mutate state (decay is applied on observe). */
    private double cost(Stat s, long now) {
        double decayed = s.costNanos * decayWeight(s, now);
        return decayed * (s.pending + 1);
    }

    /** exp(-elapsed/tau), clamped to [0,1]; 1 when no time has passed. */
    private double decayWeight(Stat s, long now) {
        long elapsed = now - s.lastUpdate;
        if (elapsed <= 0) {
            return 1.0;
        }
        return Math.exp(-((double) elapsed) / decayNanos);
    }
}
