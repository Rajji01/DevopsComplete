package com.shopflow.skiplist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class SkipListTest {

    @Test
    void staysSortedNoMatterTheInsertOrder() {
        var list = new SkipList<Integer>(new Random(1));
        List<Integer> input = new ArrayList<>(IntStream.range(0, 100).boxed().toList());
        Collections.shuffle(input, new Random(7));
        input.forEach(list::add);
        assertThat(list.toList()).isEqualTo(IntStream.range(0, 100).boxed().toList());
        assertThat(list.size()).isEqualTo(100);
    }

    @Test
    void containsReflectsAddsAndRemoves() {
        var list = new SkipList<Integer>(new Random(1));
        list.add(5);
        assertThat(list.contains(5)).isTrue();
        assertThat(list.contains(6)).isFalse();
        assertThat(list.remove(5)).isTrue();
        assertThat(list.contains(5)).isFalse();
        assertThat(list.remove(5)).isFalse(); // already gone
    }

    @Test
    void isASetNoDuplicates() {
        var list = new SkipList<String>(new Random(1));
        assertThat(list.add("x")).isTrue();
        assertThat(list.add("x")).isFalse();
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    void rangeReturnsTheInclusiveSortedSubset() {
        var list = new SkipList<Integer>(new Random(1));
        IntStream.rangeClosed(0, 20).forEach(list::add);
        assertThat(list.range(5, 10)).containsExactly(5, 6, 7, 8, 9, 10);
        assertThat(list.first()).isZero();
    }
}
