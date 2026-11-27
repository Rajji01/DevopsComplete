package com.shopflow.ratelimit;

import java.util.function.LongSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.shopflow.ratelimit.local.FixedWindowLimiter;
import com.shopflow.ratelimit.local.GcraLimiter;
import com.shopflow.ratelimit.local.LeakyBucketLimiter;
import com.shopflow.ratelimit.local.SlidingWindowCounterLimiter;
import com.shopflow.ratelimit.local.SlidingWindowLogLimiter;
import com.shopflow.ratelimit.local.TokenBucketLimiter;
import com.shopflow.ratelimit.redis.RedisRateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Turns config into the one {@link RateLimiter} the app uses. This is where "which algorithm is active"
 * and "which backend" are resolved — the only place the five implementations and two backends are named.
 * Wrapped in {@link FailOpenRateLimiter} so a backend outage is handled on purpose, not by a 500.
 */
public final class RateLimiterFactory {

    private RateLimiterFactory() {}

    public static RateLimiter create(RateLimitProperties p, LongSupplier clock,
                                     ObjectProvider<StringRedisTemplate> redis, MeterRegistry metrics) {
        RateLimitRule rule = p.toRule();
        RateLimiter core = switch (p.getBackend()) {
            case LOCAL -> local(p.getAlgorithm(), rule, clock);
            case REDIS -> new RedisRateLimiter(
                    redis.getIfAvailable(() -> {
                        throw new IllegalStateException(
                                "ratelimit.backend=REDIS but no StringRedisTemplate — need a reachable Redis");
                    }),
                    p.getAlgorithm(), rule, p.getKeyPrefix());
        };
        return new FailOpenRateLimiter(core, p.isFailOpen(), metrics);
    }

    /** The in-memory family — single node, no Redis, used for tests/dev and as a per-pod fallback. */
    public static RateLimiter local(RateLimitAlgorithm algorithm, RateLimitRule rule, LongSupplier clock) {
        return switch (algorithm) {
            case FIXED_WINDOW -> new FixedWindowLimiter(rule, clock);
            case SLIDING_WINDOW_LOG -> new SlidingWindowLogLimiter(rule, clock);
            case SLIDING_WINDOW_COUNTER -> new SlidingWindowCounterLimiter(rule, clock);
            case TOKEN_BUCKET -> new TokenBucketLimiter(rule, clock);
            case LEAKY_BUCKET -> new LeakyBucketLimiter(rule, clock);
            case GCRA -> new GcraLimiter(rule, clock);
        };
    }
}
