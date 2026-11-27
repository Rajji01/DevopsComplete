package com.shopflow.clock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.shopflow.clock.HybridLogicalClock.Timestamp;

class HybridLogicalClockTest {

    @Test
    void successiveLocalEventsStrictlyIncrease() {
        var pt = new AtomicLong(1000);
        var hlc = new HybridLogicalClock(pt::get); // physical time frozen at 1000
        Timestamp t1 = hlc.now();
        Timestamp t2 = hlc.now();
        Timestamp t3 = hlc.now();
        assertThat(t1).isLessThan(t2);
        assertThat(t2).isLessThan(t3);
        // physical time frozen -> l stays, counter climbs
        assertThat(t1).isEqualTo(new Timestamp(1000, 0));
        assertThat(t2).isEqualTo(new Timestamp(1000, 1));
        assertThat(t3).isEqualTo(new Timestamp(1000, 2));
    }

    @Test
    void counterResetsWhenPhysicalTimeAdvances() {
        var pt = new AtomicLong(1000);
        var hlc = new HybridLogicalClock(pt::get);
        hlc.now();                 // (1000, 0)
        hlc.now();                 // (1000, 1)
        pt.set(2000);              // wall clock moves forward
        Timestamp t = hlc.now();
        assertThat(t).isEqualTo(new Timestamp(2000, 0)); // l tracks physical time, counter resets
    }

    @Test
    void trackedLogicalTimeNeverRunsBehindTheWallClock() {
        var pt = new AtomicLong(5000);
        var hlc = new HybridLogicalClock(pt::get);
        Timestamp t = hlc.now();
        assertThat(t.logical()).isEqualTo(5000); // close to wall-clock, unlike a bare Lamport counter
    }

    @Test
    void receiveCarriesCausalityDespiteClockSkew() {
        // sender's clock is AHEAD of the receiver's wall clock (skew). The received timestamp must still
        // come out strictly greater on the receiver -> causality preserved even though its physical time is behind.
        var receiverClock = new AtomicLong(1000);          // receiver wall clock = 1000
        var receiver = new HybridLogicalClock(receiverClock::get);
        Timestamp fromSender = new Timestamp(5000, 7);      // sender was at l=5000 (ahead by skew)

        Timestamp afterRecv = receiver.update(fromSender);
        assertThat(afterRecv).isGreaterThan(fromSender);     // dominates the message
        assertThat(afterRecv).isEqualTo(new Timestamp(5000, 8)); // l jumps to 5000, counter = 7 + 1
    }

    @Test
    void receiveAtTheSameLogicalTimeBeatsTheLargerCounter() {
        var pt = new AtomicLong(3000);
        var local = new HybridLogicalClock(pt::get);
        local.now();                // (3000, 0)
        local.now();                // (3000, 1)
        Timestamp message = new Timestamp(3000, 5);
        Timestamp merged = local.update(message);
        assertThat(merged).isEqualTo(new Timestamp(3000, 6)); // max(1, 5) + 1
    }
}
