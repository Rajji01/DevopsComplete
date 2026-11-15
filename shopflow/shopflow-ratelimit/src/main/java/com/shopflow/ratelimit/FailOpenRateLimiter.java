package com.shopflow.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Prod scenario: what happens when the limiter ITSELF is unavailable (Redis down, timeout, a bad reply)?
 * You must decide this on purpose, because the default — an uncaught exception — fails the whole request.
 *
 *   failOpen = true  -> on error, ALLOW. Availability over protection: a rate limiter outage must not take
 *                       the API down with it. The risk is a brief unthrottled window. Right for most APIs.
 *   failOpen = false -> on error, DENY (429). Protection over availability: for an endpoint whose overload
 *                       is catastrophic (a payment/costly downstream), refusing is safer than flooding it.
 *
 * Either way we emit {@code ratelimit.backend.errors} so a silent limiter outage becomes a visible alert,
 * not a mystery. (This wraps the Redis backend; the in-memory backend never throws, so it is a clean no-op.)
 */
public final class FailOpenRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailOpenRateLimiter.class);

    private final RateLimiter delegate;
    private final boolean failOpen;
    private final MeterRegistry metrics;

    public FailOpenRateLimiter(RateLimiter delegate, boolean failOpen, MeterRegistry metrics) {
        this.delegate = delegate;
        this.failOpen = failOpen;
        this.metrics = metrics;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        try {
            return delegate.tryAcquire(key, permits);
        } catch (RuntimeException e) {
            metrics.counter("ratelimit.backend.errors", "failMode", failOpen ? "open" : "closed").increment();
            log.warn("rate-limit backend error for key {} -> failing {}: {}",
                    key, failOpen ? "OPEN (allow)" : "CLOSED (deny)", e.toString());
            // remaining/limit are unknown when the backend is down; 0/0 signals "degraded" to header-readers
            return failOpen ? RateLimitResult.allow(0, 0) : RateLimitResult.deny(0, 0, 1000);
        }
    }
}
