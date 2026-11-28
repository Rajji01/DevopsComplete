package com.shopflow.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CodelQueueTest {

    private static final long MS = 1_000_000L;

    @Test
    void aBurstThatDrainsWithinTheIntervalShedsNothing() {
        var q = new CodelQueue<Integer>(5 * MS, 100 * MS, 0);
        for (int i = 0; i < 10; i++) {
            q.offer(i, 0);
        }
        int drops = 0;
        for (int i = 0; i < 10; i++) {
            drops += q.poll((i + 1) * 10L * MS).dropped().size();   // drained 10ms..100ms, inside one interval
        }
        assertThat(drops).isZero();   // a good (transient) queue is absorbed, never shed
    }

    @Test
    void aStandingQueueShedsOnlyAfterSojournStaysAboveTargetForAFullInterval() {
        var q = new CodelQueue<Integer>(5 * MS, 100 * MS, 0);
        for (int i = 0; i < 30; i++) {
            q.offer(i, 0);
        }
        // first above-target poll only ARMS the timer (firstAbove = 50ms + 100ms interval = 150ms)
        assertThat(q.poll(50 * MS).dropped()).isEmpty();
        // still above target but before the interval elapses -> absorb
        assertThat(q.poll(120 * MS).dropped()).isEmpty();
        // past the interval -> shedding starts
        assertThat(q.poll(160 * MS).dropped()).isNotEmpty();
        // the control law schedules the next drop (~260ms)
        assertThat(q.poll(270 * MS).dropped()).isNotEmpty();
    }

    @Test
    void recoversAfterTheStandingQueueDrains_andDoesNotShedAHealthyRequest() {
        var q = new CodelQueue<Integer>(5 * MS, 100 * MS, 0);
        for (int i = 0; i < 20; i++) {
            q.offer(i, 0);
        }
        q.poll(50 * MS);                 // arm
        long t = 250 * MS;
        q.poll(t);                       // enter the drop state
        while (q.poll(t).item() != null) {
            // drain the backlog at a frozen clock
        }
        // a fresh request with a tiny sojourn must be served, not shed
        q.offer(999, 1000 * MS);
        var healthy = q.poll(1001 * MS);
        assertThat(healthy.item()).isEqualTo(999);
        assertThat(healthy.dropped()).isEmpty();
    }

    @Test
    void emptyQueuePollReturnsNothing() {
        var q = CodelQueue.<Integer>defaults();
        assertThat(q.poll(1000).item()).isNull();
        assertThat(q.poll(1000).dropped()).isEmpty();
    }

    @Test
    void rejectsBadConfig() {
        assertThatThrownBy(() -> new CodelQueue<Integer>(0, 100 * MS, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodelQueue<Integer>(5 * MS, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodelQueue<Integer>(5 * MS, 100 * MS, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
