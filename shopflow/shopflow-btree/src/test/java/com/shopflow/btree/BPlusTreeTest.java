package com.shopflow.btree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class BPlusTreeTest {

    @Test
    void putGetAndOverwrite() {
        var t = new BPlusTree<Integer, String>(4);
        t.put(1, "a");
        t.put(2, "b");
        assertThat(t.get(1)).contains("a");
        assertThat(t.get(2)).contains("b");
        assertThat(t.get(99)).isEmpty();
        t.put(1, "a2");                         // overwrite
        assertThat(t.get(1)).contains("a2");
        assertThat(t.size()).isEqualTo(2);      // overwrite didn't grow size
    }

    @Test
    void insertionsCascadeSplitsAndGrowHeightWhileStayingBalanced() {
        var t = new BPlusTree<Integer, String>(3);   // tiny order -> splits quickly
        for (int i = 1; i <= 20; i++) {
            t.put(i, "v" + i);
        }
        assertThat(t.size()).isEqualTo(20);
        assertThat(t.height()).isGreaterThan(1);     // the root split -> the tree got taller
        for (int i = 1; i <= 20; i++) {
            assertThat(t.get(i)).contains("v" + i);   // every key still findable after all the splits
        }
    }

    @Test
    void rangeScanReturnsKeysInOrderEvenWhenInsertedShuffled() {
        var t = new BPlusTree<Integer, String>(4);
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            keys.add(i);
        }
        Collections.shuffle(keys, new java.util.Random(7));
        for (int k : keys) {
            t.put(k, "v" + k);
        }
        // [10, 20): the leaf chain yields them sorted regardless of insertion order
        assertThat(t.range(10, 20)).containsExactly(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(t.range(0, 50)).hasSize(50);       // full scan
        assertThat(t.range(100, 200)).isEmpty();      // empty window
    }

    @Test
    void deleteRemovesAKeyButLeavesOthersFindable() {
        var t = new BPlusTree<Integer, String>(3);
        for (int i = 1; i <= 10; i++) {
            t.put(i, "v" + i);
        }
        assertThat(t.delete(5)).isTrue();
        assertThat(t.delete(5)).isFalse();            // already gone
        assertThat(t.get(5)).isEmpty();
        assertThat(t.size()).isEqualTo(9);
        for (int i = 1; i <= 10; i++) {
            if (i != 5) {
                assertThat(t.get(i)).contains("v" + i);
            }
        }
        assertThat(t.range(1, 11)).containsExactly(1, 2, 3, 4, 6, 7, 8, 9, 10);
    }

    @Test
    void rejectsBadOrderAndNullArgs() {
        assertThatThrownBy(() -> new BPlusTree<Integer, String>(2))
                .isInstanceOf(IllegalArgumentException.class);
        var t = new BPlusTree<Integer, String>(4);
        assertThatThrownBy(() -> t.put(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.put(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
