package com.shopflow.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SegmentIdGeneratorTest {

    @Test
    void handsOutContiguousIdsAndRefillsWhenABlockIsExhausted() {
        var gen = new SegmentIdGenerator(new InMemorySegmentAllocator(), "orders", 100);
        for (int i = 0; i < 250; i++) {          // crosses two block boundaries
            assertThat(gen.nextLong()).isEqualTo((long) i);
        }
    }

    @Test
    void twoGeneratorsOnTheSameSequenceNeverOverlap() {
        var allocator = new InMemorySegmentAllocator();
        var g1 = new SegmentIdGenerator(allocator, "orders", 100);
        var g2 = new SegmentIdGenerator(allocator, "orders", 100);
        Set<Long> ids = new HashSet<>();
        List<Long> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {          // both refill, drawing fresh non-overlapping blocks
            all.add(g1.nextLong());
            all.add(g2.nextLong());
        }
        for (long id : all) {
            assertThat(ids.add(id)).as("id %s must be unique across generators", id).isTrue();
        }
    }
}
