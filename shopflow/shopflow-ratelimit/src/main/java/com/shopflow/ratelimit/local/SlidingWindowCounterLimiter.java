package com.shopflow.ratelimit.local;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * SLIDING WINDOW COUNTER — the practical middle ground, and the usual default (Cloudflare popularised it).
 * Keep only two numbers per key: this fixed window's count and the previous one's. Estimate the rolling
 * count by weighting the previous window by how much of it still overlaps the sliding window:
 *
 *   estimate = prevCount * (fraction of previous window still inside the last {@code window}) + currCount
 *
 * O(1) memory like fixed-window, but it smooths the boundary burst instead of allowing it. The estimate
 * assumes the previous window's requests were spread evenly, so it can be off by a little under bursty
 * traffic — an accepted trade for the memory saving vs the exact log.
 */
public final class SlidingWindowCounterLimiter implements RateLimiter {

    private record Counter(long windowId, long curr, long prev) {}

    private final RateLimitRule rule;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Counter> state = new ConcurrentHashMap<>();

    public SlidingWindowCounterLimiter(RateLimitRule rule, LongSupplier clock) {
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        long wm = rule.windowMillis();
        long limit = rule.limit();
        long windowId = now / wm;
        long elapsed = now - windowId * wm;                  // how far into the current window
        double weight = (double) (wm - elapsed) / wm;        // overlap of the previous window
        RateLimitResult[] out = new RateLimitResult[1];
        state.compute(key, (k, c) -> {
            long curr, prev;
            if (c == null || c.windowId() < windowId - 1) {   // no state, or older than one window: both 0
                curr = 0;
                prev = 0;
            } else if (c.windowId() == windowId) {
                curr = c.curr();
                prev = c.prev();
            } else {                                          // exactly one window old: slide it back
                prev = c.curr();
                curr = 0;
            }
            double estimate = prev * weight + curr;
            if (estimate + permits > limit) {
                out[0] = RateLimitResult.deny((long) Math.floor(limit - estimate), limit, wm - elapsed);
                return new Counter(windowId, curr, prev);
            }
            out[0] = RateLimitResult.allow((long) Math.floor(limit - estimate - permits), limit);
            return new Counter(windowId, curr + permits, prev);
        });
        return out[0];
    }
}
