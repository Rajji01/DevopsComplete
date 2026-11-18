package com.shopflow.ratelimit.local;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * SLIDING WINDOW LOG — keep the timestamp of every request; a request is allowed iff fewer than {@code
 * limit} timestamps fall in the last {@code window}. Exact, with NO boundary burst (the window truly
 * slides). The price is memory: O(limit) timestamps per active key — on a hot key with a high limit that
 * is a lot, and it is why this is usually the algorithm you reach for only when exactness is required.
 *
 * Thread-safe: one deque per key, and all mutation happens inside {@code synchronized(dq)}.
 * (Prod note: idle keys are never evicted here — a real deployment needs a reaper or TTL; the Redis
 * backend gets this for free via PEXPIRE.)
 */
public final class SlidingWindowLogLimiter implements RateLimiter {

    private final RateLimitRule rule;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> log = new ConcurrentHashMap<>();

    public SlidingWindowLogLimiter(RateLimitRule rule, LongSupplier clock) {
        this.rule = rule;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        long now = clock.getAsLong();
        long windowMs = rule.windowMillis();
        long limit = rule.limit();
        ArrayDeque<Long> dq = log.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            long boundary = now - windowMs;
            while (!dq.isEmpty() && dq.peekFirst() <= boundary) {
                dq.pollFirst(); // drop everything older than the sliding window
            }
            if (dq.size() + permits > limit) {
                long oldest = dq.isEmpty() ? now : dq.peekFirst();
                return RateLimitResult.deny(limit - dq.size(), limit, (oldest + windowMs) - now);
            }
            for (int i = 0; i < permits; i++) {
                dq.addLast(now);
            }
            return RateLimitResult.allow(limit - dq.size(), limit);
        }
    }
}
