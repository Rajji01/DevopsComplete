package com.shopflow.ratelimit;

/**
 * The classic algorithms. Pick one with {@code ratelimit.algorithm}. Short version of the trade-offs
 * (the README has the long version with diagrams):
 *
 *  FIXED_WINDOW            — count per clock-aligned window. Cheapest + simplest. Flaw: a 2x burst can
 *                            cross the boundary (last second of window N + first second of N+1).
 *  SLIDING_WINDOW_LOG      — store a timestamp per request; count those within the last window. Exact,
 *                            no boundary burst. Cost: O(limit) memory per key — the expensive one.
 *  SLIDING_WINDOW_COUNTER  — weight the previous window's count by how much of it still overlaps.
 *                            O(1) memory, smooths the boundary. Slightly approximate. The usual default.
 *  TOKEN_BUCKET            — refill tokens at a steady rate up to a capacity; a request spends tokens.
 *                            Allows controlled bursts (up to capacity), great for APIs. Stripe/AWS use it.
 *  LEAKY_BUCKET            — a queue that drains at a fixed rate; rejects when full. Enforces a SMOOTH
 *                            output rate (no bursts) — good for protecting a fragile downstream.
 *  GCRA                    — the token bucket computed from the other side: one timestamp (Theoretical
 *                            Arrival Time), no token count, no refill. Same burst-to-capacity behaviour,
 *                            O(1) state, exact Retry-After; what redis-cell uses, ideal for a single-key
 *                            distributed limiter.
 */
public enum RateLimitAlgorithm {
    FIXED_WINDOW,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER,
    TOKEN_BUCKET,
    LEAKY_BUCKET,
    GCRA
}
