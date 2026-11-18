package com.shopflow.resilience;

import java.util.concurrent.Semaphore;

/**
 * Bulkhead: cap how many calls can run against a dependency AT ONCE. Named after a ship's watertight
 * compartments — one flooded section doesn't sink the ship. If a downstream gets slow, unbounded callers
 * pile up and exhaust the thread pool, taking the WHOLE service down with it; the bulkhead refuses past N
 * so the rest of the service keeps serving. Acquire before the call, release in a finally.
 */
public final class Bulkhead {

    private final Semaphore permits;

    public Bulkhead(int maxConcurrent) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    /** @return true if a slot was free (caller MUST {@link #release()} when done); false = rejected now. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
