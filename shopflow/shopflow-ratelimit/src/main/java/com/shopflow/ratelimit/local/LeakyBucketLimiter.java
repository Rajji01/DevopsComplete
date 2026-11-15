package com.shopflow.ratelimit.local;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * LEAKY BUCKET (meter form) — picture a bucket with a hole: it drains ("leaks") at a fixed {@code
 * refillPerSecond} and each request pours in one unit; if it would overflow {@code capacity}, reject.
 *
 * The difference from token bucket is the shape it enforces: token bucket lets you BURST up to capacity,
 * leaky bucket admits work at a SMOOTH, near-constant rate (the level only falls at the leak rate, so you
 * can never push faster than it drains for long). Reach for it to protect a fragile downstream that hates
 * spikes — a steady trickle is the whole point.
 */
public final class LeakyBucketLimiter implements RateLimiter {

    private record Meter(double level, long ts) {}

    private final RateLimitRule rule;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Meter> state = new ConcurrentHashMap<>();

    public LeakyBucketLimiter(RateLimitRule rule, LongSupplier clock) {
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        double cap = rule.capacity();
        double leak = rule.refillPerSecond();
        RateLimitResult[] out = new RateLimitResult[1];
        state.compute(key, (k, m) -> {
            double level = (m == null) ? 0 : m.level();
            long last = (m == null) ? now : m.ts();
            level = Math.max(0, level - Math.max(0, now - last) / 1000.0 * leak); // drain since last touch
            if (level + permits <= cap) {
                double next = level + permits;
                out[0] = RateLimitResult.allow((long) Math.floor(cap - next), (long) cap);
                return new Meter(next, now);
            }
            long retry = (long) Math.ceil((level + permits - cap) / leak * 1000); // ms until it drains enough
            out[0] = RateLimitResult.deny((long) Math.floor(cap - level), (long) cap, retry);
            return new Meter(level, now);
        });
        return out[0];
    }
}
