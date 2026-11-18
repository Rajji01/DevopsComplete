package com.shopflow.resilience;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Retry a flaky action a few times with backoff between attempts. Two things that are easy to get wrong and
 * are explicit here:
 *  • only retry what is actually RETRYABLE — a transient timeout/5xx, not a 400/validation error (retrying a
 *    deterministic failure just wastes attempts and delays the inevitable). That's the {@code retryOn} predicate.
 *  • the {@link Sleeper} is injected, so tests verify the backoff schedule WITHOUT real waiting.
 */
public final class Retry {

    /** The wait between attempts — real {@code Thread::sleep} in prod, a recorder in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final BackoffStrategy backoff;
    private final Predicate<Exception> retryOn;
    private final Sleeper sleeper;

    public Retry(int maxAttempts, BackoffStrategy backoff, Predicate<Exception> retryOn, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
        this.retryOn = retryOn;
        this.sleeper = sleeper;
    }

    public <T> T execute(Callable<T> action) throws Exception {
        int attempt = 1;
        while (true) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt >= maxAttempts || !retryOn.test(e)) {
                    throw e;                                  // out of attempts, or a non-retryable error
                }
                try {
                    sleeper.sleep(backoff.delayMillis(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;                                  // interrupted while backing off -> give up
                }
                attempt++;
            }
        }
    }
}
