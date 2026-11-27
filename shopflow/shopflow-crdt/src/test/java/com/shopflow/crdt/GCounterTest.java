package com.shopflow.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GCounterTest {

    @Test
    void independentIncrementsConvergeToTheSumAfterMerge() {
        var a = new GCounter();
        var b = new GCounter();
        a.increment("a", 3);
        b.increment("b", 4);
        b.increment("b");        // +1

        a.mergeFrom(b);
        assertThat(a.value()).isEqualTo(8); // 3 (a) + 5 (b)
    }

    @Test
    void mergeIsCommutative() {
        var a = new GCounter();
        var b = new GCounter();
        a.increment("a", 10);
        b.increment("b", 7);

        var aThenB = new GCounter();
        aThenB.mergeFrom(a);
        aThenB.mergeFrom(b);

        var bThenA = new GCounter();
        bThenA.mergeFrom(b);
        bThenA.mergeFrom(a);

        assertThat(aThenB.value()).isEqualTo(bThenA.value()).isEqualTo(17);
    }

    @Test
    void mergeIsIdempotent() {
        var a = new GCounter();
        a.increment("a", 5);
        var b = new GCounter();
        b.increment("b", 2);
        a.mergeFrom(b);
        long once = a.value();
        a.mergeFrom(b); // merging the same state again changes nothing
        a.mergeFrom(b);
        assertThat(a.value()).isEqualTo(once).isEqualTo(7);
    }

    @Test
    void mergeTakesThePerReplicaMaxSoReplayedGossipDoesNotDoubleCount() {
        var a = new GCounter();
        a.increment("a", 5);
        var b = new GCounter();
        b.mergeFrom(a);   // b learns a=5
        a.increment("a", 3); // a is now 8
        b.mergeFrom(a);   // max(5, 8) = 8, not 5+8
        assertThat(b.value()).isEqualTo(8);
    }

    @Test
    void refusesToDecrement() {
        var g = new GCounter();
        assertThatThrownBy(() -> g.increment("a", -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
