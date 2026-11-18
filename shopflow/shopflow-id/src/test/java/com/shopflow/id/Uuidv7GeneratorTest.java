package com.shopflow.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class Uuidv7GeneratorTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final LongSupplier clock = now::get;

    @Test
    void carriesVersion7AndTheTimestampInTheHighBits() {
        UUID u = new Uuidv7Generator(clock, new Random(1)).next();
        assertThat(u.version()).isEqualTo(7);
        assertThat(u.getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void producesDistinctIds() {
        var g = new Uuidv7Generator(clock, new Random(1));
        assertThat(g.next()).isNotEqualTo(g.next());
    }
}
