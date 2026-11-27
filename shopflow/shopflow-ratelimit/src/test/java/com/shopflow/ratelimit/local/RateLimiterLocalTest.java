package com.shopflow.ratelimit.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * Pure-logic tests for the in-memory algorithms — no Redis, no Spring. A fake clock ({@link AtomicLong})
 * drives time, so "wait a second" is a cheap, deterministic {@code advance(1000)}. These also document
 * each algorithm's defining behaviour. (The Redis/Lua backend mirrors this logic and is exercised live.)
 */
class RateLimiterLocalTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;

    private void advance(long ms) {
        now.addAndGet(ms);
    }

    private static boolean allowed(RateLimiter rl, String key) {
        return rl.tryAcquire(key).allowed();
    }

    @Test
    void fixedWindow_allowsUpToLimitThenResetsAtTheBoundary() {
        var rl = new FixedWindowLimiter(RateLimitRule.perWindow(3, Duration.ofSeconds(1)), clock);
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isFalse();      // 4th in the same window -> denied
        assertThat(allowed(rl, "b")).isTrue();        // a different key has its own counter
        advance(1000);                                 // new window
        assertThat(allowed(rl, "a")).isTrue();
    }

    @Test
    void slidingWindowLog_deniesUntilTheOldestEntryLeavesTheWindow() {
        var rl = new SlidingWindowLogLimiter(RateLimitRule.perWindow(3, Duration.ofSeconds(1)), clock);
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        advance(500);
        assertThat(allowed(rl, "a")).isFalse();        // still 3 in the last second
        advance(600);                                  // now > 1s since the first three -> they slid out
        assertThat(allowed(rl, "a")).isTrue();
    }

    @Test
    void slidingWindowCounter_blendsPreviousWindowAndRecoversOverTime() {
        var rl = new SlidingWindowCounterLimiter(RateLimitRule.perWindow(3, Duration.ofSeconds(1)), clock);
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isFalse();
        advance(1000);                                 // next window, previous weighted ~fully -> still blocked
        assertThat(allowed(rl, "a")).isFalse();
        advance(2000);                                 // two windows on, previous weight gone
        assertThat(allowed(rl, "a")).isTrue();
    }

    @Test
    void tokenBucket_burstsToCapacityThenRefillsAtRate() {
        var rl = new TokenBucketLimiter(RateLimitRule.bucket(3, 1.0), clock); // cap 3, +1 token/sec
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();         // burst of 3
        assertThat(allowed(rl, "a")).isFalse();        // bucket empty
        advance(1000);                                 // +1 token
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isFalse();        // and empty again
    }

    @Test
    void leakyBucket_admitsAsItDrainsNeverFasterThanTheLeak() {
        var rl = new LeakyBucketLimiter(RateLimitRule.bucket(3, 1.0), clock); // cap 3, leaks 1/sec
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();         // fills to 3
        assertThat(allowed(rl, "a")).isFalse();        // full
        advance(1000);                                 // drains 1 -> room for one more
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isFalse();
    }

    @Test
    void gcra_burstsToCapacityThenPacesAtTheRate() {
        var rl = new GcraLimiter(RateLimitRule.bucket(3, 1.0), clock); // cap 3, 1 permit/sec
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();
        assertThat(allowed(rl, "a")).isTrue();         // burst of exactly 3 from idle
        assertThat(allowed(rl, "a")).isFalse();        // 4th denied — same shape as the token bucket
        advance(1000);                                 // one emission interval later
        assertThat(allowed(rl, "a")).isTrue();         // one more conforms
        assertThat(allowed(rl, "a")).isFalse();
    }

    @Test
    void gcra_deniedCarriesExactRetryAfter() {
        var rl = new GcraLimiter(RateLimitRule.bucket(1, 1.0), clock); // cap 1, 1/sec
        assertThat(rl.tryAcquire("a").allowed()).isTrue();
        var denied = rl.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(1000); // exactly one emission interval
    }

    @Test
    void denied_resultCarriesRetryAfter() {
        var rl = new TokenBucketLimiter(RateLimitRule.bucket(1, 1.0), clock);
        assertThat(rl.tryAcquire("a").allowed()).isTrue();
        var denied = rl.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isGreaterThan(0);
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }
}
