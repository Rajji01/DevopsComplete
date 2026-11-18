package com.shopflow.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LongSupplier clock = now::get;

    @Test
    void idsAreUniqueAndIncreasingWithinOneMillisecond() {
        var gen = new SnowflakeIdGenerator(1, 0, clock);  // clock fixed -> all in the same ms
        long prev = Long.MIN_VALUE;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 4096; i++) {                  // exactly the per-ms sequence capacity
            long id = gen.nextLong();
            assertThat(id).isGreaterThan(prev);
            assertThat(seen.add(id)).isTrue();
            prev = id;
        }
    }

    @Test
    void sequenceResetsAndIdsKeepIncreasingOnANewMillisecond() {
        var gen = new SnowflakeIdGenerator(1, 0, clock);
        long a = gen.nextLong();
        now.incrementAndGet();                            // +1 ms
        long b = gen.nextLong();
        assertThat(b).isGreaterThan(a);
    }

    @Test
    void refusesWhenTheClockMovesBackwards() {
        var gen = new SnowflakeIdGenerator(1, 0, clock);
        gen.nextLong();
        now.addAndGet(-10);                               // NTP step back
        assertThatThrownBy(gen::nextLong).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encodesTheNodeIdInTheMiddleBits() {
        var gen = new SnowflakeIdGenerator(5, 0, clock);
        long id = gen.nextLong();
        assertThat((id >>> 12) & 0x3FF).isEqualTo(5L);
    }
}
