package com.shopflow.sketch;

/**
 * Bloom filter: "have I probably seen this key?" in a fraction of the memory a real set would use. Set k
 * bits per key; a lookup that finds any of its k bits unset means DEFINITELY NOT present. If all are set it
 * is PROBABLY present — bits can collide, so there are false positives but **never false negatives**. That
 * asymmetry is the whole value: use it as a cheap gate before an expensive check (does this row exist? is
 * this URL seen? — if the filter says no, skip the DB entirely; if yes, pay for the real lookup).
 *
 * Sizing from the classic formulas: for n items at false-positive rate p, bits m = -n·ln p / (ln2)², and
 * k = (m/n)·ln2 hash functions. More bits / right k = lower p.
 */
public final class BloomFilter<T> {

    private final long[] words;
    private final int bits;
    private final int hashes;

    private BloomFilter(int bits, int hashes) {
        this.bits = bits;
        this.hashes = hashes;
        this.words = new long[(bits + 63) >>> 6];
    }

    public static <T> BloomFilter<T> create(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0 || falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("need n > 0 and 0 < fpp < 1");
        }
        int m = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
        int k = Math.max(1, (int) Math.round((double) m / expectedInsertions * Math.log(2)));
        return new BloomFilter<>(m, k);
    }

    public void add(T key) {
        long h = Hashes.hash64(key.toString());
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < hashes; i++) {
            setBit(index(h1, h2, i));
        }
    }

    public boolean mightContain(T key) {
        long h = Hashes.hash64(key.toString());
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < hashes; i++) {
            if (!getBit(index(h1, h2, i))) {
                return false; // a clear bit => definitely never added (no false negatives)
            }
        }
        return true;          // all set => probably present (maybe a false positive)
    }

    public int sizeInBits() {
        return bits;
    }

    public int hashCount() {
        return hashes;
    }

    private int index(int h1, int h2, int i) {
        long combined = (h1 & 0xffffffffL) + (long) i * (h2 & 0xffffffffL);
        return (int) Long.remainderUnsigned(combined, bits);
    }

    private void setBit(int idx) {
        words[idx >>> 6] |= 1L << (idx & 63);
    }

    private boolean getBit(int idx) {
        return (words[idx >>> 6] & (1L << (idx & 63))) != 0;
    }
}
