package com.shopflow.ratelimit.local;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * GCRA — the Generic Cell Rate Algorithm (from ATM traffic shaping; what redis-cell implements). It is the
 * token/leaky bucket's identical twin, but computed from the OTHER direction: instead of tracking a level of
 * tokens that refills over time, it tracks a single timestamp — the Theoretical Arrival Time (TAT), the
 * earliest instant at which a perfectly-paced stream would next be allowed. One {@code long} per key, no
 * stored token count, no background refill thread.
 *
 * <h3>The arithmetic</h3>
 * With emission interval {@code T = 1000/rate} ms per permit and a burst of {@code capacity}:
 * <pre>
 *   newTat = max(storedTat, now) + permits*T
 *   allow  iff  newTat - now <= capacity*T          // i.e. the projected arrival isn't further than a full bucket ahead
 * </pre>
 * On allow, store {@code newTat}; a request of {@code capacity} permits from idle lands exactly on the limit
 * (burst of exactly {@code capacity}), and one more is rejected. On deny, nothing is stored and the overflow
 * {@code (newTat - now) - capacity*T} is precisely the time until the request would conform — the
 * {@code Retry-After}.
 *
 * <h3>Why bother, if it equals a token bucket?</h3>
 * Same behaviour, cheaper and race-free-r state: one timestamp instead of (tokens, lastRefill), and the
 * "allow + new state" is a single compare-and-set of that timestamp — which is exactly why it is the
 * algorithm of choice for a distributed limiter backed by one Redis key (redis-cell's {@code CL.THROTTLE}).
 * It also yields an exact {@code Retry-After} for free.
 *
 * <p>Lazy and O(1) per call; a key untouched for an hour costs nothing in between. Uses
 * {@link ConcurrentHashMap#compute} so the test-and-set per key is atomic.
 */
public final class GcraLimiter implements RateLimiter {

    private final double emissionIntervalMillis; // T: ms per permit
    private final double limitMillis;            // capacity * T: a full bucket's worth of time
    private final long capacity;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> tats = new ConcurrentHashMap<>();

    public GcraLimiter(RateLimitRule rule, LongSupplier clock) {
        if (rule.refillPerSecond() <= 0) {
            throw new IllegalArgumentException("GCRA needs a positive rate (refillPerSecond)");
        }
        this.capacity = rule.capacity();
        this.emissionIntervalMillis = 1000.0 / rule.refillPerSecond();
        this.limitMillis = capacity * emissionIntervalMillis;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        double increment = permits * emissionIntervalMillis;
        RateLimitResult[] out = new RateLimitResult[1];
        tats.compute(key, (k, stored) -> {
            double tat = (stored == null) ? now : stored;
            double newTat = Math.max(tat, now) + increment;
            double aheadOfNow = newTat - now;              // how far the projected arrival sits in the future
            if (aheadOfNow <= limitMillis) {
                long remaining = (long) Math.floor((limitMillis - aheadOfNow) / emissionIntervalMillis);
                out[0] = RateLimitResult.allow(remaining, capacity);
                return (long) Math.ceil(newTat);           // conforming: advance the TAT
            }
            long retry = (long) Math.ceil(aheadOfNow - limitMillis); // exact time until it would conform
            out[0] = RateLimitResult.deny(0, capacity, retry);
            return stored;                                 // rejected: TAT unchanged
        });
        return out[0];
    }
}
