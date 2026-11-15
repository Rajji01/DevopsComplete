package com.shopflow.id;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * UUID version 7 (RFC 9562): a standard {@link UUID} whose high bits are a 48-bit millisecond timestamp, so
 * it is time-ordered like ULID but drops straight into anything that already expects a {@code UUID}. Layout:
 *
 *   most-significant long: [ 48-bit ms ][ 4-bit version=7 ][ 12 random ]
 *   least-significant long: [ 2-bit variant=10 ][ 62 random ]
 *
 * Use it over UUIDv4 for the same reason as ULID — a time-sortable key keeps DB inserts at the tail of the
 * index instead of scattering them. Over Snowflake when you want a standard UUID and 128 bits is fine.
 */
public final class Uuidv7Generator implements IdGenerator {

    private final LongSupplier clock;
    private final Random random;

    public Uuidv7Generator() {
        this(System::currentTimeMillis, new SecureRandom());
    }

    public Uuidv7Generator(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long ms = clock.getAsLong() & 0xFFFFFFFFFFFFL;                  // low 48 bits
        long msb = (ms << 16) | (0x7L << 12) | (random.nextInt(1 << 12)); // ts | version 7 | 12 random
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10 | 62 random
        return new UUID(msb, lsb);
    }

    @Override
    public String nextId() {
        return next().toString();
    }
}
