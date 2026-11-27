package com.shopflow.clock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.shopflow.clock.VectorClock.Ordering;

class VectorClockTest {

    @Test
    void identicalVectorsAreEqual() {
        var a = new VectorClock();
        var b = new VectorClock();
        a.increment("n1");
        b.increment("n1");
        assertThat(a.compareTo(b)).isEqualTo(Ordering.EQUAL);
    }

    @Test
    void aCausalChainIsOrderedBeforeToAfter() {
        // n1 does an event, then sends to n2 which merges + bumps -> n2's vector causally AFTER n1's
        var n1 = new VectorClock();
        n1.increment("n1");             // {n1:1}

        var n2 = new VectorClock();
        n2.mergeFrom(n1);               // n2 receives n1's state
        n2.increment("n2");             // {n1:1, n2:1}  -> happened-after n1

        assertThat(n1.happenedBefore(n2)).isTrue();
        assertThat(n1.compareTo(n2)).isEqualTo(Ordering.BEFORE);
        assertThat(n2.compareTo(n1)).isEqualTo(Ordering.AFTER);
        assertThat(n1.isConcurrentWith(n2)).isFalse();
    }

    @Test
    void independentEventsOnDifferentNodesAreConcurrent() {
        // both start from a shared past, then each advances its OWN node with no message between them
        var shared = new VectorClock();
        shared.increment("n1");         // {n1:1}

        var a = new VectorClock();
        a.mergeFrom(shared);
        a.increment("n1");              // {n1:2}

        var b = new VectorClock();
        b.mergeFrom(shared);
        b.increment("n2");              // {n1:1, n2:1}

        // a has n1 ahead, b has n2 ahead -> neither dominates -> CONCURRENT (a Lamport clock couldn't tell)
        assertThat(a.isConcurrentWith(b)).isTrue();
        assertThat(b.isConcurrentWith(a)).isTrue();
        assertThat(a.compareTo(b)).isEqualTo(Ordering.CONCURRENT);
        assertThat(a.happenedBefore(b)).isFalse();
    }

    @Test
    void mergeTakesTheElementWiseMax() {
        var a = new VectorClock();
        a.increment("n1");
        a.increment("n1");              // {n1:2}
        a.increment("n3");              // {n1:2, n3:1}

        var b = new VectorClock();
        b.increment("n1");              // {n1:1}
        b.increment("n2");              // {n1:1, n2:1}

        a.mergeFrom(b);                 // max per node: {n1:2, n2:1, n3:1}
        assertThat(a.get("n1")).isEqualTo(2);
        assertThat(a.get("n2")).isEqualTo(1);
        assertThat(a.get("n3")).isEqualTo(1);
    }

    @Test
    void unknownNodeReadsAsZero() {
        assertThat(new VectorClock().get("never-seen")).isZero();
    }
}
