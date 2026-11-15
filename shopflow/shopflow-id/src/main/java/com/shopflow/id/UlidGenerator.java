package com.shopflow.id;

import java.security.SecureRandom;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * ULID: a 128-bit, lexicographically-sortable id as a 26-char Crockford base32 string —
 *   [ 48-bit millisecond timestamp -> 10 chars ][ 80 bits of randomness -> 16 chars ]
 *
 * Why over a UUIDv4: v4 is random, so as a DB key it scatters inserts across the index (page splits, poor
 * cache locality). ULID's time prefix makes string order == time order, so inserts stay at the end — and it
 * needs NO node-id coordination (unlike Snowflake). Cost: 26 chars vs a 64-bit long.
 *
 * Monotonic within the same millisecond: instead of fresh randomness (which could sort out of order for two
 * ids in the same ms), we increment the previous random suffix by one — so later always sorts after earlier.
 */
public final class UlidGenerator implements IdGenerator {

    // Crockford base32: no I, L, O, U (to avoid confusion); chars are in ascending order, so symbol value
    // order == character order == lexicographic order — which is what keeps the whole id sortable.
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final LongSupplier clock;
    private final Random random;

    private long lastMs = -1L;
    private final int[] suffix = new int[16]; // 16 base32 symbols = exactly 80 bits of randomness

    public UlidGenerator() {
        this(System::currentTimeMillis, new SecureRandom());
    }

    public UlidGenerator(LongSupplier clock, Random random) {
        this.clock = clock;
        this.random = random;
    }

    @Override
    public synchronized String nextId() {
        long ms = clock.getAsLong();
        if (ms == lastMs) {
            if (!incrementSuffix()) {   // overflowed all 80 bits in one ms (never happens in practice)
                fillSuffix();
            }
        } else {
            lastMs = ms;
            fillSuffix();
        }
        return encodeTime(ms) + encodeSuffix();
    }

    private void fillSuffix() {
        for (int i = 0; i < suffix.length; i++) {
            suffix[i] = random.nextInt(32);
        }
    }

    /** Base-32 increment from the least-significant symbol; false only on full overflow. */
    private boolean incrementSuffix() {
        for (int i = suffix.length - 1; i >= 0; i--) {
            if (suffix[i] < 31) {
                suffix[i]++;
                return true;
            }
            suffix[i] = 0; // carry
        }
        return false;
    }

    // 48-bit timestamp -> 10 chars, most-significant 5 bits first
    private static String encodeTime(long ms) {
        char[] out = new char[10];
        for (int i = 0; i < 10; i++) {
            out[i] = ALPHABET[(int) ((ms >>> (45 - 5 * i)) & 0x1F)];
        }
        return new String(out);
    }

    private String encodeSuffix() {
        char[] out = new char[16];
        for (int i = 0; i < 16; i++) {
            out[i] = ALPHABET[suffix[i]];
        }
        return new String(out);
    }
}
