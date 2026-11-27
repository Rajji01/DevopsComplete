package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AimdConcurrencyLimiterTest {

    @Test
    void startsAtTheInitialLimit() {
        var l = new AimdConcurrencyLimiter(10, 1, 100, 0.9, 1000);
        assertThat(l.limit()).isEqualTo(10);
    }

    @Test
    void shedsWhenInflightReachesTheLimit() {
        var l = new AimdConcurrencyLimiter(2, 1, 10, 0.9, 1000);
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isTrue();   // inflight == limit == 2
        assertThat(l.tryAcquire()).isFalse();  // shed
        assertThat(l.inflight()).isEqualTo(2);
    }

    @Test
    void healthyLoadThatPressesTheLimitGrowsIt() {
        var l = new AimdConcurrencyLimiter(10, 1, 100, 0.9, 1000);
        for (int i = 0; i < 10; i++) {
            l.tryAcquire();                    // fill to the limit (pressing)
        }
        for (int i = 0; i < 40; i++) {
            l.onSuccess(5);                    // fast completion while pressing -> +1
            l.tryAcquire();                    // keep it pressed
        }
        assertThat(l.limit()).isGreaterThan(10).isLessThanOrEqualTo(100);
    }

    @Test
    void aSlowSuccessBacksTheLimitOff() {
        var l = new AimdConcurrencyLimiter(50, 1, 100, 0.5, 1000);
        l.tryAcquire();
        l.onSuccess(5000);                     // slower than the 1000ms timeout -> multiplicative decrease
        assertThat(l.limit()).isEqualTo(25);   // 50 * 0.5
    }

    @Test
    void aDropBacksTheLimitOff() {
        var l = new AimdConcurrencyLimiter(50, 1, 100, 0.5, 1000);
        l.tryAcquire();
        l.onDropped();
        assertThat(l.limit()).isEqualTo(25);
    }

    @Test
    void neverGrowsAboveMaxOrShrinksBelowMin() {
        var up = new AimdConcurrencyLimiter(99, 1, 100, 0.9, 1000);
        for (int i = 0; i < 99; i++) {
            up.tryAcquire();
        }
        for (int i = 0; i < 20; i++) {
            up.onSuccess(1);
            up.tryAcquire();
        }
        assertThat(up.limit()).isEqualTo(100); // capped

        var down = new AimdConcurrencyLimiter(2, 2, 100, 0.5, 1000);
        down.tryAcquire();
        down.onDropped();
        assertThat(down.limit()).isEqualTo(2); // floored at min
    }

    @Test
    void idleHealthyTrafficDoesNotInflateTheLimit() {
        var l = new AimdConcurrencyLimiter(10, 1, 100, 0.9, 1000);
        // one request at a time, never pressing the limit -> it should stay put
        for (int i = 0; i < 50; i++) {
            l.tryAcquire();
            l.onSuccess(5);
        }
        assertThat(l.limit()).isEqualTo(10);
    }

    @Test
    void rejectsBadConfiguration() {
        assertThatThrownBy(() -> new AimdConcurrencyLimiter(10, 0, 100, 0.9, 1000))
                .isInstanceOf(IllegalArgumentException.class); // minLimit < 1
        assertThatThrownBy(() -> new AimdConcurrencyLimiter(200, 1, 100, 0.9, 1000))
                .isInstanceOf(IllegalArgumentException.class); // initial > max
        assertThatThrownBy(() -> new AimdConcurrencyLimiter(10, 1, 100, 1.0, 1000))
                .isInstanceOf(IllegalArgumentException.class); // backoffRatio not in (0,1)
    }
}
