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
            // avg is 1000; a robust bound that proves no node starves (<6.6%) or hoards (>60%). With 200
            // vnodes + an avalanched hash the real spread is far tighter (~800-1200); more vnodes = tighter.
            assertThat(c).isBetween(200, 1800);
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
