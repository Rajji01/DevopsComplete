package com.shopflow.balance.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MaglevHashTest {

    @Test
    void distributesKeysEvenlyAcrossBackends() {
        var maglev = new MaglevHash<>(List.of("a", "b", "c", "d", "e"));
        Map<String, Integer> counts = new HashMap<>();
        int keys = 100_000;
        for (int i = 0; i < keys; i++) {
            counts.merge(maglev.lookup("key-" + i), 1, Integer::sum);
        }
        int ideal = keys / 5;
        // Maglev's table is even to within one slot, so routed load is within a few % of ideal
        for (int c : counts.values()) {
            assertThat(c).isBetween((int) (ideal * 0.9), (int) (ideal * 1.1));
        }
    }

    @Test
    void lookupIsStableForTheSameKey() {
        var maglev = new MaglevHash<>(List.of("a", "b", "c"));
        String first = maglev.lookup("user-42");
        for (int i = 0; i < 100; i++) {
            assertThat(maglev.lookup("user-42")).isEqualTo(first);
        }
    }

    @Test
    void removingABackendReassignsOnlyASmallFraction() {
        var before = new MaglevHash<>(List.of("a", "b", "c", "d", "e"));
        var after = new MaglevHash<>(List.of("a", "b", "c", "d")); // e removed
        int keys = 100_000;
        int moved = 0;
        for (int i = 0; i < keys; i++) {
            String k = "key-" + i;
            if (!before.lookup(k).equals(after.lookup(k))) {
                moved++;
            }
        }
        // e held ~1/5 (must move); Maglev adds only a little extra churn -> far below mod-N's ~100%
        assertThat((double) moved / keys).isLessThan(0.30);
    }

    @Test
    void rejectsBadConfiguration() {
        assertThatThrownBy(() -> new MaglevHash<>(List.<String>of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaglevHash<>(List.of("a", "b"), 10)) // 10 is not prime
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaglevHash<>(List.of("a", "b", "c"), 3)) // size must exceed backends
                .isInstanceOf(IllegalArgumentException.class);
    }
}
