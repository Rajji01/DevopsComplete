package com.shopflow.resilience;

import java.util.ArrayDeque;

/**
 * Phi (φ) accrual failure detector — the "is that node dead?" algorithm Cassandra, Akka/Pekko and
 * ScyllaDB use for cluster membership (Hayashibara et al., 2004). It is the gossip-world cousin of the
 * {@link CircuitBreaker} in this package: both decide whether something is healthy, but a breaker watches
 * your own calls' outcomes, while this watches a remote peer's heartbeats.
 *
 * <p><b>The problem with a fixed timeout.</b> The naive liveness check is "no heartbeat for T ms ⇒ dead".
 * Pick T too small and a GC pause or a network hiccup gets a healthy node wrongly convicted (and
 * re-replicated, and re-joined — expensive churn); pick T too large and a real crash goes undetected for
 * ages. There is no single T that is right for both a quiet LAN and a jittery WAN, and the answer is binary
 * when reality is a gradient.
 *
 * <p><b>The accrual idea.</b> Instead of a yes/no verdict, output a <i>suspicion level</i> φ that rises
 * continuously the longer a heartbeat is overdue, scaled by how regular that peer's heartbeats have
 * historically been. A peer that always beats every 1s ±5ms becomes suspicious almost the instant it is
 * late; a peer that beats every 1s ±400ms is given more slack. The application picks a threshold on φ
 * (e.g. convict at φ ≥ 8) and so separates the "how do I measure doubt" mechanism from the "how much doubt
 * do I tolerate" policy.
 *
 * <pre>
 *   φ(now) = -log10( 1 - F(now - lastHeartbeat) )
 * </pre>
 *
 * where F is the CDF of a normal distribution whose mean and standard deviation are estimated from a sliding
 * window of recent heartbeat inter-arrival times. So φ is the negative log of the probability that a
 * heartbeat this late could still be "on time": φ=1 ≈ 10% chance we're wrong to suspect, φ=2 ≈ 1%, φ=3 ≈ 0.1%.
 *
 * <p>This implementation mirrors Akka's: the Gaussian tail is evaluated with a cheap, well-known logistic
 * approximation (no erf needed); a {@code minStdDeviation} floor stops a suspiciously-regular peer from
 * producing an explosive φ off one slightly-late beat (σ→0 would make the tail collapse); an
 * {@code acceptableHeartbeatPause} is added to the mean so an expected pause (GC, scheduler) doesn't count
 * against the node; and the window is seeded from a {@code firstHeartbeatEstimate} so φ is meaningful before
 * real samples accumulate.
 *
 * <p>Time is passed in by the caller (millis) rather than read from a clock, so tests are fully deterministic;
 * in production pass {@code System.currentTimeMillis()}.
 */
public final class PhiAccrualFailureDetector {

    private final int maxSampleSize;
    private final double minStdDeviationMillis;
    private final double acceptableHeartbeatPauseMillis;

    // sliding window of inter-arrival intervals, with running sum + sum-of-squares for O(1) mean/variance
    private final ArrayDeque<Double> intervals = new ArrayDeque<>();
    private double sum;
    private double sumOfSquares;

    private long lastHeartbeatMillis = -1;

    /**
     * @param maxSampleSize                  how many recent inter-arrival intervals feed the estimate (e.g. 200)
     * @param minStdDeviationMillis          floor on σ, so a very regular peer isn't convicted off one late beat
     * @param acceptableHeartbeatPauseMillis slack added to the mean for expected pauses (GC, scheduling)
     * @param firstHeartbeatEstimateMillis   seed mean before real samples exist (σ seeded at estimate/4)
     */
    public PhiAccrualFailureDetector(int maxSampleSize, double minStdDeviationMillis,
                                     double acceptableHeartbeatPauseMillis, double firstHeartbeatEstimateMillis) {
        if (maxSampleSize < 1) {
            throw new IllegalArgumentException("maxSampleSize must be >= 1");
        }
        if (minStdDeviationMillis <= 0 || firstHeartbeatEstimateMillis <= 0) {
            throw new IllegalArgumentException("minStdDeviation and firstHeartbeatEstimate must be positive");
        }
        if (acceptableHeartbeatPauseMillis < 0) {
            throw new IllegalArgumentException("acceptableHeartbeatPause must be >= 0");
        }
        this.maxSampleSize = maxSampleSize;
        this.minStdDeviationMillis = minStdDeviationMillis;
        this.acceptableHeartbeatPauseMillis = acceptableHeartbeatPauseMillis;
        // seed the window so the distribution is sane before the first real interval arrives
        double sd = firstHeartbeatEstimateMillis / 4.0;
        addInterval(firstHeartbeatEstimateMillis - sd);
        addInterval(firstHeartbeatEstimateMillis + sd);
    }

    /** Record a heartbeat received at {@code nowMillis}. The first one only sets the baseline. */
    public synchronized void heartbeat(long nowMillis) {
        if (lastHeartbeatMillis >= 0) {
            addInterval((double) (nowMillis - lastHeartbeatMillis));
        }
        lastHeartbeatMillis = nowMillis;
    }

    /** Current suspicion level. 0.0 before any heartbeat has been seen. */
    public synchronized double phi(long nowMillis) {
        if (lastHeartbeatMillis < 0) {
            return 0.0;
        }
        long timeDiff = nowMillis - lastHeartbeatMillis;
        double mean = mean() + acceptableHeartbeatPauseMillis;
        double stdDeviation = Math.max(stdDeviation(), minStdDeviationMillis);
        return phi(timeDiff, mean, stdDeviation);
    }

    /** Convenience: is the peer still trusted at this suspicion threshold? (e.g. threshold 8). */
    public synchronized boolean isAvailable(long nowMillis, double threshold) {
        return phi(nowMillis) < threshold;
    }

    // --- internals ---------------------------------------------------------

    /**
     * φ from the time since the last beat and the window's (mean, σ), using Akka's logistic approximation of
     * the Gaussian complementary CDF — accurate to a few parts in 1000 and much cheaper than erf.
     */
    static double phi(long timeDiff, double mean, double stdDeviation) {
        double y = (timeDiff - mean) / stdDeviation;
        double e = Math.exp(-y * (1.5976 + 0.070566 * y * y));
        if (timeDiff > mean) {
            return -Math.log10(e / (1.0 + e));
        }
        return -Math.log10(1.0 - 1.0 / (1.0 + e));
    }

    private void addInterval(double interval) {
        intervals.addLast(interval);
        sum += interval;
        sumOfSquares += interval * interval;
        if (intervals.size() > maxSampleSize) {
            double old = intervals.removeFirst();
            sum -= old;
            sumOfSquares -= old * old;
        }
    }

    private double mean() {
        return sum / intervals.size();
    }

    private double stdDeviation() {
        double m = mean();
        double variance = Math.max(0.0, (sumOfSquares / intervals.size()) - m * m); // population variance
        return Math.sqrt(variance);
    }
}
