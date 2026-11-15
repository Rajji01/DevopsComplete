package com.shopflow.resilience;

/** How long to wait before retry number {@code attempt} (1-based). See {@link Backoffs} for the common ones. */
@FunctionalInterface
public interface BackoffStrategy {

    long delayMillis(int attempt);
}
