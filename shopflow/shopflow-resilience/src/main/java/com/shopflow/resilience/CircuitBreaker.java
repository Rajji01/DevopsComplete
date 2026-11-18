package com.shopflow.resilience;

import java.util.function.LongSupplier;

/**
 * Circuit breaker: stop hammering a failing dependency. When too many recent calls fail it trips OPEN and
 * every call fails FAST (no waiting on a dead service, no piling up threads); after a cool-off it goes
 * HALF_OPEN and lets a few trial calls through to see if the dependency recovered.
 *
 *   CLOSED  --(failure rate >= threshold over the window)-->  OPEN
 *   OPEN    --(openDuration elapsed)-->                       HALF_OPEN
 *   HALF_OPEN --(all trial calls succeed)-->                  CLOSED
 *   HALF_OPEN --(any trial call fails)-->                     OPEN
 *
 * Usage is permission-based: ask {@link #tryAcquirePermission()}; if true, make the call and report the
 * outcome with {@link #recordSuccess()} / {@link #recordFailure()}. Count-based sliding window; clock
 * injected so the OPEN->HALF_OPEN timeout is testable without sleeping. (ShopFlow's real breaker is
 * resilience4j on InventoryClient — this is the same machine, built to show how it works.)
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final CircuitBreakerConfig config;
    private final LongSupplier clock;

    private State state = State.CLOSED;

    // count-based ring over the last N outcomes (true = failure)
    private final boolean[] ring;
    private int pos = 0;
    private int size = 0;
    private int failures = 0;

    private long openedAt = 0;
    private int halfOpenSuccesses = 0;
    private int halfOpenInFlight = 0;

    public CircuitBreaker(CircuitBreakerConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.ring = new boolean[config.slidingWindow()];
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean tryAcquirePermission() {
        switch (state) {
            case OPEN:
                if (clock.getAsLong() - openedAt >= config.openDurationMillis()) {
                    toHalfOpen();
                    return permitHalfOpen();
                }
                return false; // still cooling off -> fail fast
            case HALF_OPEN:
                return permitHalfOpen();
            case CLOSED:
            default:
                return true;
        }
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= config.halfOpenPermittedCalls()) {
                toClosed(); // the dependency recovered
            }
        } else if (state == State.CLOSED) {
            record(false);
        }
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            toOpen(); // still broken — back to fail-fast
        } else if (state == State.CLOSED) {
            record(true);
            if (size >= config.minimumCalls() && failureRate() >= config.failureRateThreshold()) {
                toOpen();
            }
        }
    }

    private boolean permitHalfOpen() {
        if (halfOpenInFlight >= config.halfOpenPermittedCalls()) {
            return false; // only a few probes at a time
        }
        halfOpenInFlight++;
        return true;
    }

    private void record(boolean failure) {
        if (size == ring.length) {           // window full -> evict the oldest
            if (ring[pos]) {
                failures--;
            }
        } else {
            size++;
        }
        ring[pos] = failure;
        if (failure) {
            failures++;
        }
        pos = (pos + 1) % ring.length;
    }

    private double failureRate() {
        return size == 0 ? 0.0 : (double) failures / size;
    }

    private void toOpen() {
        state = State.OPEN;
        openedAt = clock.getAsLong();
        halfOpenSuccesses = 0;
        halfOpenInFlight = 0;
    }

    private void toHalfOpen() {
        state = State.HALF_OPEN;
        halfOpenSuccesses = 0;
        halfOpenInFlight = 0;
    }

    private void toClosed() {
        state = State.CLOSED;
        pos = 0;
        size = 0;
        failures = 0;
    }
}
