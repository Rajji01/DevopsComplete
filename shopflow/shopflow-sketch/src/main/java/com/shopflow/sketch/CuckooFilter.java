package com.shopflow.sketch;

import java.util.Random;

/**
 * Cuckoo filter: approximate set membership that — unlike a plain {@link BloomFilter} — supports DELETE, and
 * unlike a {@link CountingBloomFilter} does it in less space with better lookup locality. Instead of setting
 * k bits, it stores a short FINGERPRINT of each item in one of two candidate buckets and resolves collisions
 * by "cuckoo hashing" (kick an existing fingerprint to its alternate bucket to make room). A lookup reads
 * just two buckets; no false negatives, only false positives (a fingerprint collision).
 *
 * <h3>The partial-key cuckoo trick</h3>
 * Normally cuckoo hashing needs the key to compute a slot's alternate location, but a filter only stores
 * fingerprints, not keys. The trick: the two buckets are {@code i1 = hash(x)} and
 * {@code i2 = i1 XOR hash(fingerprint)}. Because XOR is its own inverse, you can recover EITHER bucket from
 * the other using only the fingerprint ({@code i1 = i2 XOR hash(fp)}) — so an eviction can relocate a
 * fingerprint without ever knowing which item it came from. Bucket count is a power of two so the XOR stays
 * in range.
 *
 * <h3>Trade-offs vs. Bloom</h3>
 * Supports delete (Bloom can't) and typically uses less space than a counting Bloom at the same
 * false-positive rate, with only two bucket reads per lookup (cache-friendly). The cost: inserts can FAIL
 * when the table is near-full and a kick chain exceeds the limit — {@link #add} returns {@code false} rather
 * than silently corrupting, which is your signal to size up. Delete is only safe for items you actually
 * inserted (deleting a never-inserted item can remove a colliding fingerprint).
 *
 * <p>Not thread-safe: one writer per instance, or guard externally.
 */
public final class CuckooFilter {

    private static final int BUCKET_SIZE = 4;   // fingerprints per bucket (4 is the standard sweet spot)
    private static final int MAX_KICKS = 500;   // give up relocating after this many — table is too full
    private static final double LOAD_FACTOR = 0.94;

    private final byte[] fingerprints;          // numBuckets * BUCKET_SIZE slots; 0 == empty
    private final int numBuckets;
    private final int mask;                     // numBuckets - 1 (numBuckets is a power of two)
    private final Random random;
    private int size;

    private CuckooFilter(int numBuckets, Random random) {
        this.numBuckets = numBuckets;
        this.mask = numBuckets - 1;
        this.fingerprints = new byte[numBuckets * BUCKET_SIZE];
        this.random = random;
    }

    /** Size for roughly {@code capacity} items at the standard ~0.94 load factor. */
    public static CuckooFilter create(int capacity) {
        return create(capacity, new Random());
    }

    /** Deterministic variant: inject the {@link Random} used to pick an eviction victim (for tests). */
    public static CuckooFilter create(int capacity, Random random) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        int needed = (int) Math.ceil(capacity / LOAD_FACTOR / BUCKET_SIZE);
        int buckets = Integer.highestOneBit(Math.max(1, needed - 1)) << 1; // next power of two >= needed
        return new CuckooFilter(buckets, random);
    }

    /** Insert {@code item}. Returns {@code false} if the table is too full to place it (grow the filter). */
    public boolean add(String item) {
        long h = Hashes.hash64(item);
        byte fp = fingerprint(h);
        int i1 = index(h);
        int i2 = altIndex(i1, fp);
        if (insertInto(i1, fp) || insertInto(i2, fp)) {
            size++;
            return true;
        }
        // both candidate buckets full — start kicking from a random one
        int i = random.nextBoolean() ? i1 : i2;
        byte carried = fp;
        for (int n = 0; n < MAX_KICKS; n++) {
            int slot = random.nextInt(BUCKET_SIZE);
            int pos = i * BUCKET_SIZE + slot;
            byte evicted = fingerprints[pos];  // swap our carried fingerprint in, carry the evicted one out
            fingerprints[pos] = carried;
            carried = evicted;
            i = altIndex(i, carried);          // the evicted fp goes to ITS alternate bucket
            if (insertInto(i, carried)) {
                size++;
                return true;
            }
        }
        // couldn't reseat within MAX_KICKS: the carried fingerprint is homeless. Put it back is not possible
        // cleanly, so report failure — the caller must size up. (size is unchanged; the table is at capacity.)
        return false;
    }

    /** Might this item be present? {@code false} = definitely absent; {@code true} = probably present. */
    public boolean mightContain(String item) {
        long h = Hashes.hash64(item);
        byte fp = fingerprint(h);
        int i1 = index(h);
        return bucketHas(i1, fp) || bucketHas(altIndex(i1, fp), fp);
    }

    /** Remove one copy of {@code item}. Returns {@code true} if a matching fingerprint was found and removed. */
    public boolean remove(String item) {
        long h = Hashes.hash64(item);
        byte fp = fingerprint(h);
        int i1 = index(h);
        if (removeFrom(i1, fp) || removeFrom(altIndex(i1, fp), fp)) {
            size--;
            return true;
        }
        return false;
    }

    /** Number of items currently stored (decremented by {@link #remove}). */
    public int size() {
        return size;
    }

    // --- internals ---

    private boolean insertInto(int bucket, byte fp) {
        int base = bucket * BUCKET_SIZE;
        for (int s = 0; s < BUCKET_SIZE; s++) {
            if (fingerprints[base + s] == 0) {
                fingerprints[base + s] = fp;
                return true;
            }
        }
        return false;
    }

    private boolean bucketHas(int bucket, byte fp) {
        int base = bucket * BUCKET_SIZE;
        for (int s = 0; s < BUCKET_SIZE; s++) {
            if (fingerprints[base + s] == fp) {
                return true;
            }
        }
        return false;
    }

    private boolean removeFrom(int bucket, byte fp) {
        int base = bucket * BUCKET_SIZE;
        for (int s = 0; s < BUCKET_SIZE; s++) {
            if (fingerprints[base + s] == fp) {
                fingerprints[base + s] = 0;
                return true;
            }
        }
        return false;
    }

    /** A non-zero 8-bit fingerprint (0 is reserved for "empty slot"). */
    private byte fingerprint(long h) {
        int f = (int) (h & 0xFF);
        if (f == 0) {
            f = 1;
        }
        return (byte) f;
    }

    private int index(long h) {
        return (int) ((h >>> 32) & mask);
    }

    /** The other candidate bucket: i XOR hash(fp), masked. XOR is its own inverse, so it works from either. */
    private int altIndex(int bucket, byte fp) {
        int fpHash = (fp & 0xFF) * 0x5bd1e995; // mix the fingerprint so buckets spread out
        return (bucket ^ fpHash) & mask;
    }
}
