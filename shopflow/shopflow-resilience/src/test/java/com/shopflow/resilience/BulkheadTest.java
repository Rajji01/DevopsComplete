package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BulkheadTest {

    @Test
    void admitsUpToTheLimitThenRejectsUntilAReleaseFreesASlot() {
        var bulkhead = new Bulkhead(2);
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isTrue();
        assertThat(bulkhead.tryAcquire()).isFalse();   // full
        assertThat(bulkhead.availablePermits()).isZero();
        bulkhead.release();
        assertThat(bulkhead.tryAcquire()).isTrue();     // a slot freed up
    }
}
