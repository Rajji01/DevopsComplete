package com.shopflow.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PNCounterTest {

    @Test
    void incrementsAndDecrementsNetOut() {
        var c = new PNCounter();
        c.increment("a", 10);
        c.decrement("a", 3);
        c.increment("a");  // +1
        c.decrement("a");  // -1
        assertThat(c.value()).isEqualTo(7);
    }

    @Test
    void canGoNegative() {
        var c = new PNCounter();
        c.decrement("a", 5);
        assertThat(c.value()).isEqualTo(-5);
    }

    @Test
    void twoReplicasConvergeAcrossIncrementsAndDecrements() {
        var a = new PNCounter();
        var b = new PNCounter();
        a.increment("a", 8);
        a.decrement("a", 2);   // a-side net +6
        b.increment("b", 5);
        b.decrement("b", 9);   // b-side net -4

        a.mergeFrom(b);
        b.mergeFrom(a);
        assertThat(a.value()).isEqualTo(b.value()).isEqualTo(2); // 6 + (-4)
    }

    @Test
    void mergeIsIdempotent() {
        var a = new PNCounter();
        a.increment("a", 4);
        a.decrement("a", 1);
        var b = new PNCounter();
        b.increment("b", 2);
        a.mergeFrom(b);
        long once = a.value();
        a.mergeFrom(b);
        assertThat(a.value()).isEqualTo(once).isEqualTo(5); // (4-1) + 2
    }
}
