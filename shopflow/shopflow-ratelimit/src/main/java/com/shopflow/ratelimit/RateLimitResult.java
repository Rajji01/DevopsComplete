package com.shopflow.ratelimit;

/**
 * The outcome of one {@link RateLimiter#tryAcquire}. Carries enough to set the standard response
 * headers a good API returns on every call — not just a boolean:
 *   X-RateLimit-Limit, X-RateLimit-Remaining  (let a well-behaved client self-throttle)
 *   Retry-After                                (on a 429, how long to back off — in SECONDS, per RFC)
 *
 * @param allowed          was the request permitted
 * @param remaining        units left in the current allowance (0 when denied-at-the-edge)
 * @param limit            the ceiling (window limit, or bucket capacity)
 * @param retryAfterMillis when denied, ms until at least one permit frees up (0 when allowed)
 */
public record RateLimitResult(boolean allowed, long remaining, long limit, long retryAfterMillis) {

    public static RateLimitResult allow(long remaining, long limit) {
        return new RateLimitResult(true, Math.max(0, remaining), limit, 0);
    }

    public static RateLimitResult deny(long remaining, long limit, long retryAfterMillis) {
        return new RateLimitResult(false, Math.max(0, remaining), limit, Math.max(0, retryAfterMillis));
    }

    /** Retry-After header is whole seconds; always round UP so we never tell a client to retry too early. */
    public long retryAfterSeconds() {
        return (retryAfterMillis + 999) / 1000;
    }
}
