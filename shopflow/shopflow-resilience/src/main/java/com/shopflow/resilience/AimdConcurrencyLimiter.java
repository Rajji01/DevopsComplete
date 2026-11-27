package com.shopflow.resilience;

/**
 * Adaptive concurrency limiter (AIMD) — finds the right concurrency limit by itself, instead of the hand-
 * tuned fixed number a {@link Bulkhead} uses. It borrows TCP's congestion-control rule: Additive Increase /
 * Multiplicative Decrease. While requests complete fast and the limit is actually being pressed, nudge the
 * limit up by 1; the moment a request is slow (over the timeout) or dropped, cut the limit by a backoff
 * factor. The effect is that the limit rides just below the point where latency starts climbing — the real
 * capacity — and backs off hard the instant the downstream shows stress. (This is the shape of Netflix's
 * concurrency-limits AIMDLimit; a fixed Bulkhead can't track capacity that changes with load or a slow
 * dependency.)
 *
 * <h3>Why additive-up but multiplicative-down</h3>
 * The asymmetry is the whole point, same as TCP: probe for more capacity gently (one at a time, so you don't
 * overshoot and cause the overload you're trying to avoid), but when overload appears, retreat fast
 * (multiply down) to relieve the downstream immediately. Symmetric steps would either probe too slowly or
 * crash the downstream on every blip.
 *
 * <h3>Use</h3>
 * {@link #tryAcquire} before a call (returns false = SHED this request, you're at the limit); then report the
 * outcome with {@link #onSuccess} (with its latency) or {@link #onDropped} (timeout/rejection/error). The
 * limit only grows when the limit was actually in use at sample time, so an idle service doesn't inflate its
 * limit to the max and then admit a stampede.
 *
 * <p>Synchronized; a single limiter instance is shared across the calling threads.
 */
public final class AimdConcurrencyLimiter {

    private final int minLimit;
    private final int maxLimit;
    private final double backoffRatio;
    private final long timeoutMillis;
    private int limit;
    private int inflight;

    /**
     * @param initialLimit starting limit
     * @param minLimit     never drop below this (keep making a little progress even under heavy overload)
     * @param maxLimit     never grow above this (a sanity ceiling)
     * @param backoffRatio multiplicative-decrease factor on overload, in (0,1) — e.g. 0.9
     * @param timeoutMillis a success slower than this counts as an overload signal (decrease), not a probe
     */
    public AimdConcurrencyLimiter(int initialLimit, int minLimit, int maxLimit,
                                  double backoffRatio, long timeoutMillis) {
        if (minLimit < 1 || maxLimit < minLimit || initialLimit < minLimit || initialLimit > maxLimit) {
            throw new IllegalArgumentException("need 1 <= minLimit <= initialLimit <= maxLimit");
        }
        if (backoffRatio <= 0 || backoffRatio >= 1) {
            throw new IllegalArgumentException("backoffRatio must be in (0, 1)");
        }
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.backoffRatio = backoffRatio;
        this.timeoutMillis = timeoutMillis;
        this.limit = initialLimit;
    }

    /** Try to admit a request. {@code false} = at the limit, SHED it (and don't report an outcome). */
    public synchronized boolean tryAcquire() {
        if (inflight >= limit) {
            return false;
        }
        inflight++;
        return true;
    }

    /** Report a completed request and its latency. Fast + pressing the limit → grow; slow → back off. */
    public synchronized void onSuccess(long rttMillis) {
        boolean wasPressing = inflight * 2 >= limit; // the limit was actually being used at sample time
        inflight = Math.max(0, inflight - 1);
        if (rttMillis > timeoutMillis) {
            decrease();                 // a slow success is still an overload signal
        } else if (wasPressing) {
            increase();                 // healthy and at the edge -> probe for more
        }
        // healthy but idle: leave the limit alone (don't inflate toward max while under-used)
    }

    /** Report a dropped request (timeout, rejection, error): an overload signal → back off. */
    public synchronized void onDropped() {
        inflight = Math.max(0, inflight - 1);
        decrease();
    }

    public synchronized int limit() {
        return limit;
    }

    public synchronized int inflight() {
        return inflight;
    }

    private void increase() {
        limit = Math.min(maxLimit, limit + 1);
    }

    private void decrease() {
        limit = Math.max(minLimit, (int) (limit * backoffRatio));
    }
}
