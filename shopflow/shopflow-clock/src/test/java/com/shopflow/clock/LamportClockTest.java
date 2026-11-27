package com.shopflow.clock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LamportClockTest {

    @Test
    void localEventsIncreaseMonotonically() {
        var c = new LamportClock();
        assertThat(c.tick()).isEqualTo(1);
        assertThat(c.tick()).isEqualTo(2);
        assertThat(c.time()).isEqualTo(2);
    }

    @Test
    void receiveAdvancesPastBothClocks() {
        var c = new LamportClock();
        c.tick();                       // local time = 1
        long after = c.onReceive(10);   // max(1,10)+1 = 11
        assertThat(after).isEqualTo(11);
        assertThat(c.time()).isEqualTo(11);
    }

    @Test
    void happensBeforeImpliesStrictlyIncreasingTimestamps() {
        // model a -> message -> b across two processes; L(a) < L(b) must hold
        var p1 = new LamportClock();
        var p2 = new LamportClock();
        p2.tick();                      // p2 does some unrelated work first (time 1)
        long sendA = p1.sendTimestamp(); // event a on p1, stamp the message (time 1)
        long recvB = p2.onReceive(sendA); // event b on p2 receives a
        assertThat(recvB).isGreaterThan(sendA); // a -> b  =>  L(a) < L(b)
    }
}
