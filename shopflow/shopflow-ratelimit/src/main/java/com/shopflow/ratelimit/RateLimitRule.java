package com.shopflow.ratelimit;

import java.time.Duration;

/**
 * The numeric policy, written once and read by whichever algorithm is active. Different algorithms
 * are naturally parameterised differently, so this holds the superset and each uses its slice:
 *
 *   window algorithms (fixed / sliding)  use  {@link #limit} + {@link #windowMillis}
 *   bucket  algorithms (token / leaky)   use  {@link #capacity} + {@link #refillPerSecond}
 *
 * Use the factories so both views stay consistent (e.g. "100 / minute" is also ~1.67 permits/sec).
 *
 * @param limit           max permits per window (window algos)
 * @param windowMillis    window size in ms (window algos)
 * @param capacity        bucket size = biggest instantaneous burst allowed (bucket algos)
 * @param refillPerSecond tokens added / units drained per second (bucket algos)
 */
public record RateLimitRule(long limit, long windowMillis, long capacity, double refillPerSecond) {

    public RateLimitRule {
        if (limit <= 0 && capacity <= 0) {
            throw new IllegalArgumentException("a rule needs a positive limit or capacity");
        }
    }

    /** "{@code limit} requests per {@code window}". Fills the bucket view too (capacity = limit). */
    public static RateLimitRule perWindow(long limit, Duration window) {
        long ms = window.toMillis();
        double perSec = ms == 0 ? limit : limit * 1000.0 / ms;
        return new RateLimitRule(limit, ms, limit, perSec);
    }

    /** "burst up to {@code capacity}, refilling {@code refillPerSecond}/s". Fills the window view too. */
    public static RateLimitRule bucket(long capacity, double refillPerSecond) {
        long ms = refillPerSecond == 0 ? 0 : Math.round(capacity / refillPerSecond * 1000);
        return new RateLimitRule(capacity, ms, capacity, refillPerSecond);
    }
}
