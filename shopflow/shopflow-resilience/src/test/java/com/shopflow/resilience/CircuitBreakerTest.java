package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;
    // window 10, min 5 calls, trip at 50% failures, open for 1000ms, 2 trial calls to re-close
    private final CircuitBreakerConfig config = new CircuitBreakerConfig(10, 5, 0.5, 1000, 2);

    private void open(CircuitBreaker cb) {
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure(); // 5 calls, 3 failures = 60% >= 50% -> OPEN
    }

    @Test
    void opensWhenTheFailureRateCrossesTheThreshold() {
        var cb = new CircuitBreaker(config, clock);
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED); // only 4 calls < minimumCalls
        cb.recordFailure();                                            // 5th -> 60% failures
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.tryAcquirePermission()).isFalse();               // fails fast while open
    }

    @Test
    void movesToHalfOpenAfterTheTimeoutThenClosesWhenTrialsSucceed() {
        var cb = new CircuitBreaker(config, clock);
        open(cb);
        now.addAndGet(1000);                                           // cool-off elapses
        assertThat(cb.tryAcquirePermission()).isTrue();                // trial 1 (now HALF_OPEN)
        cb.recordSuccess();
        assertThat(cb.tryAcquirePermission()).isTrue();                // trial 2
        cb.recordSuccess();                                            // 2 successes == permitted -> CLOSED
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void reopensIfATrialCallFailsInHalfOpen() {
        var cb = new CircuitBreaker(config, clock);
        open(cb);
        now.addAndGet(1000);
        assertThat(cb.tryAcquirePermission()).isTrue();                // half-open trial
        cb.recordFailure();                                            // still broken
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
