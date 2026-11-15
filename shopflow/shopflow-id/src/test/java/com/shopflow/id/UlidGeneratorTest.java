package com.shopflow.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class UlidGeneratorTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final LongSupplier clock = now::get;
    private final Random seeded = new Random(42);

    @Test
    void is26CrockfordBase32Chars() {
        String id = new UlidGenerator(clock, seeded).nextId();
        assertThat(id).hasSize(26);
        assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{26}"); // Crockford: no I, L, O, U
    }

    @Test
    void laterTimestampSortsStrictlyAfterEarlier() {
        var g = new UlidGenerator(clock, seeded);
        String a = g.nextId();
        now.addAndGet(1);
        String b = g.nextId();
        assertThat(b.compareTo(a)).isGreaterThan(0);
    }

    @Test
    void monotonicWithinTheSameMillisecond() {
        var g = new UlidGenerator(clock, seeded);
        String a = g.nextId();
        String b = g.nextId();                 // same ms -> suffix is incremented, so b sorts after a
        assertThat(b.compareTo(a)).isGreaterThan(0);
    }
}
