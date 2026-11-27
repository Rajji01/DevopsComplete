package com.shopflow.balance.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BoundedLoadHashRingTest {

    @Test
    void noNodeEverExceedsTheBoundedCap() {
        var ring = new BoundedLoadHashRing<String>(100, 0.25); // cap = ceil(1.25 * total / nodes)
        ring.addNode("a");
        ring.addNode("b");
        ring.addNode("c");
        int total = 300;
        for (int i = 0; i < total; i++) {
            ring.assign("key-" + i);
        }
        int cap = (int) Math.ceil(1.25 * total / 3); // 125
        assertThat(ring.load("a")).isLessThanOrEqualTo(cap);
        assertThat(ring.load("b")).isLessThanOrEqualTo(cap);
        assertThat(ring.load("c")).isLessThanOrEqualTo(cap);
        assertThat(ring.load("a") + ring.load("b") + ring.load("c")).isEqualTo(total); // all placed
    }

    @Test
    void assignmentIsIdempotentAndStable() {
        var ring = new BoundedLoadHashRing<String>(50, 0.25);
        ring.addNode("a");
        ring.addNode("b");
        String first = ring.assign("user-42");
        for (int i = 0; i < 10; i++) {
            assertThat(ring.assign("user-42")).isEqualTo(first); // same key -> same node, no double count
        }
        assertThat(ring.load("a") + ring.load("b")).isEqualTo(1);
    }

    @Test
    void releaseFreesCapacity() {
        var ring = new BoundedLoadHashRing<String>(50, 0.0); // ε=0 -> cap = ceil(total/nodes), tightest
        ring.addNode("a");
        ring.addNode("b");
        ring.assign("k1");
        ring.assign("k2");
        int before = ring.load("a") + ring.load("b");
        ring.release("k1");
        assertThat(ring.load("a") + ring.load("b")).isEqualTo(before - 1);
    }

    @Test
    void tighterEpsilonSpreadsLoadMoreEvenly() {
        var tight = new BoundedLoadHashRing<String>(100, 0.1);
        for (String n : new String[]{"a", "b", "c"}) {
            tight.addNode(n);
        }
        for (int i = 0; i < 300; i++) {
            tight.assign("k-" + i);
        }
        int cap = (int) Math.ceil(1.1 * 300 / 3); // 110
        assertThat(tight.load("a")).isLessThanOrEqualTo(cap);
        assertThat(tight.load("b")).isLessThanOrEqualTo(cap);
        assertThat(tight.load("c")).isLessThanOrEqualTo(cap);
    }

    @Test
    void rejectsBadInputAndEmptyRing() {
        assertThatThrownBy(() -> new BoundedLoadHashRing<String>(0, 0.25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLoadHashRing<String>(10, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLoadHashRing<String>(10, 0.25).assign("x")).isInstanceOf(IllegalStateException.class);
    }
}
