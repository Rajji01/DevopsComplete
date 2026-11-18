package com.shopflow.ratelimit.local;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * FIXED WINDOW — one counter per clock-aligned window (e.g. each calendar minute). Reset to 0 when the
 * window rolls over.
 *
 * Pro: trivial, O(1) memory per key. Con: the boundary burst — "limit" requests in the last moment of
 * one window plus "limit" in the first moment of the next lets 2x through in a sub-window span. Use it
 * when a brief 2x overshoot is acceptable; otherwise prefer a sliding variant.
 *
 * Thread-safe: ConcurrentHashMap.compute runs its remapping atomically for the key, so the read (current
 * count) and the write (incremented count) can never interleave with another thread on the same key.
 */
public final class FixedWindowLimiter implements RateLimiter {

    private record Window(long id, long count) {}

    private final RateLimitRule rule;
    private final LongSupplier clock; // epoch millis; injectable so tests can drive time
    private final ConcurrentHashMap<String, Window> state = new ConcurrentHashMap<>();

    public FixedWindowLimiter(RateLimitRule rule, LongSupplier clock) {
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        long windowId = now / rule.windowMillis();
        long untilReset = (windowId + 1) * rule.windowMillis() - now;
        RateLimitResult[] out = new RateLimitResult[1];
        state.compute(key, (k, cur) -> {
            long count = (cur == null || cur.id() != windowId) ? 0 : cur.count();
            if (count + permits > rule.limit()) {
                out[0] = RateLimitResult.deny(rule.limit() - count, rule.limit(), untilReset);
                return new Window(windowId, count); // also rolls a stale window to 0
            }
            long next = count + permits;
            out[0] = RateLimitResult.allow(rule.limit() - next, rule.limit());
            return new Window(windowId, next);
        });
        return out[0];
    }
}
