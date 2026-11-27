package com.shopflow.histogram;

import java.util.Map;
import java.util.TreeMap;

/**
 * DDSketch (Masson, Rim &amp; Lee, VLDB 2019): streaming quantiles — p50 / p99 / p999 — in bounded memory
 * with a RELATIVE-error guarantee. That guarantee is the whole point for latency SLOs: a sketch with
 * α = 1% returns a p99 within 1% of the true p99 whether it's 8 ms or 8 s, because the buckets are spaced
 * geometrically (a fixed percentage apart), not by an absolute width you'd have to guess up front.
 *
 * <h3>How it works</h3>
 * With γ = (1+α)/(1−α), a positive value {@code x} falls in bucket {@code i = ceil(log_γ x)}, which covers
 * {@code (γ^(i-1), γ^i]}. Every value in that bucket is within a factor of γ of the bucket's representative
 * value {@code 2γ^i/(γ+1)} (its geometric midpoint), so reporting that midpoint for a quantile is accurate
 * to (γ−1)/(γ+1) = α — the relative-error bound. Counting per bucket is all the state; there is no stored
 * sample, so memory is the number of DISTINCT buckets touched (log in the value range), not the stream length.
 *
 * <h3>Why relative, not absolute (vs. a fixed-bucket / HdrHistogram-style sketch)</h3>
 * A naive histogram with fixed-width buckets is either too coarse for the fast requests or wastes buckets on
 * the slow tail. DDSketch's geometric buckets give the same percentage accuracy everywhere, which matches
 * how latency SLOs are actually written ("p99 &lt; 200 ms" cares about percentage, not milliseconds at 8 s).
 *
 * <h3>Mergeable</h3>
 * Two sketches with the same α merge by adding their per-bucket counts ({@link #mergeFrom}) — so each shard
 * keeps a sketch of its own latencies and a global p99 is the merged sketch, with no raw samples shipped and
 * the same α guarantee preserved. (This teaching version is unbounded in bucket count; the paper adds a
 * collapse step to cap memory for extreme ranges.)
 *
 * <p>Handles {@code x == 0} with a dedicated zero counter; negative values are rejected (this is for
 * latencies / positive magnitudes). Not thread-safe: one writer per instance, or guard externally.
 */
public final class DDSketch {

    private final double alpha;
    private final double gamma;
    private final double logGamma;
    private final TreeMap<Integer, Long> buckets = new TreeMap<>(); // bucket index -> count, ascending
    private long zeroCount;
    private long count;

    private DDSketch(double alpha) {
        this.alpha = alpha;
        this.gamma = (1 + alpha) / (1 - alpha);
        this.logGamma = Math.log(gamma);
    }

    /**
     * @param alpha the relative-error bound, 0 &lt; α &lt; 1 (e.g. 0.01 = every reported quantile within 1%
     *              of the true value). Smaller α = more buckets = more memory.
     */
    public static DDSketch create(double alpha) {
        if (alpha <= 0 || alpha >= 1) {
            throw new IllegalArgumentException("relative accuracy must be in (0, 1), got: " + alpha);
        }
        return new DDSketch(alpha);
    }

    /** Record one observation (a latency, a size). {@code value} must be &gt;= 0. */
    public void add(double value) {
        if (value < 0) {
            throw new IllegalArgumentException("DDSketch is for non-negative values, got: " + value);
        }
        if (value == 0) {
            zeroCount++;
        } else {
            int index = (int) Math.ceil(Math.log(value) / logGamma);
            buckets.merge(index, 1L, Long::sum);
        }
        count++;
    }

    /**
     * The estimated {@code q}-quantile (q in [0,1]; 0.99 = p99), within relative error α of the true value.
     * Throws if empty.
     */
    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("quantile must be in [0, 1], got: " + q);
        }
        if (count == 0) {
            throw new IllegalStateException("quantile of an empty sketch");
        }
        double rank = q * (count - 1); // 0-indexed rank of the wanted element
        double cumulative = 0;
        if (zeroCount > 0) {
            cumulative += zeroCount;
            if (cumulative > rank) {
                return 0.0;
            }
        }
        for (Map.Entry<Integer, Long> bucket : buckets.entrySet()) {
            cumulative += bucket.getValue();
            if (cumulative > rank) {
                int i = bucket.getKey();
                return 2 * Math.pow(gamma, i) / (gamma + 1); // the bucket's representative value
            }
        }
        // floating-point edge at q == 1: fall through to the largest bucket
        int i = buckets.lastKey();
        return 2 * Math.pow(gamma, i) / (gamma + 1);
    }

    /** Fold {@code other} (same α) into this sketch: per-bucket count addition. Estimates the union's quantiles. */
    public void mergeFrom(DDSketch other) {
        if (other.alpha != alpha) {
            throw new IllegalArgumentException("cannot merge α=" + other.alpha + " into α=" + alpha);
        }
        other.buckets.forEach((index, c) -> buckets.merge(index, c, Long::sum));
        zeroCount += other.zeroCount;
        count += other.count;
    }

    /** Total observations recorded. */
    public long count() {
        return count;
    }

    /** The relative-error guarantee (α): every reported quantile is within this fraction of the true value. */
    public double relativeError() {
        return alpha;
    }
}
