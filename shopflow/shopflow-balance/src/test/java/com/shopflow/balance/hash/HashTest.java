package com.shopflow.balance.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HashTest {

    @Test
    void hashRing_removingANodeOnlyMovesThatNodesKeys() {
        var ring = new HashRing<String>(150);
        ring.add("A");
        ring.add("B");
        ring.add("C");

        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            before.put("key-" + i, ring.route("key-" + i));
        }
        long onC = before.values().stream().filter("C"::equals).count();

        ring.remove("C");

        int moved = 0;
        int movedThatWereNotOnC = 0;
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = ring.route(e.getKey());
            if (!now.equals(e.getValue())) {
                moved++;
                if (!"C".equals(e.getValue())) {
                    movedThatWereNotOnC++;
                }
            }
        }
        assertThat(movedThatWereNotOnC).isZero();      // consistent hashing: untouched keys stay put
        assertThat(moved).isEqualTo((int) onC);        // only C's keys relocated (~1/N of the total)
    }

    @Test
    void hashRing_distributesAcrossAllNodesWithVirtualNodes() {
        var ring = new HashRing<String>(200);
        ring.add("A");
        ring.add("B");
        ring.add("C");

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            counts.merge(ring.route("k" + i), 1, Integer::sum);
        }
        assertThat(counts).hasSize(3);
        for (int c : counts.values()) {
            assertThat(c).isBetween(400, 1600);        // avg 1000; loose bound just proves no node starves
        }
    }

    @Test
    void rendezvous_removingANodeLeavesEveryOtherKeyWhereItWas() {
        var hrw = new RendezvousHash<String>();
        hrw.add("A");
        hrw.add("B");
        hrw.add("C");

        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            before.put("k" + i, hrw.route("k" + i));
        }
        hrw.remove("B");
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (!"B".equals(e.getValue())) {
                assertThat(hrw.route(e.getKey())).isEqualTo(e.getValue());
            }
        }
    }
}
