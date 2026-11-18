package com.shopflow.resilience;

/**
 * @param slidingWindow          how many recent calls the failure rate is measured over (count-based)
 * @param minimumCalls           don't trip on thin data — need at least this many calls in the window first
 * @param failureRateThreshold   0.0–1.0; at/above this failure fraction the breaker opens
 * @param openDurationMillis     how long to stay OPEN (fail fast) before probing again
 * @param halfOpenPermittedCalls trial calls allowed in HALF_OPEN; all must succeed to close
 */
public record CircuitBreakerConfig(int slidingWindow, int minimumCalls, double failureRateThreshold,
                                   long openDurationMillis, int halfOpenPermittedCalls) {

    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(10, 5, 0.5, 10_000, 3);
    }
}
