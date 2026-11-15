package com.shopflow.sketch;

/**
 * A Bloom filter you can DELETE from. A plain Bloom filter can't remove an item — clearing its bits might
 * clear bits another item shares. So instead of one bit per slot, keep a small COUNTER: add increments the
 * k slots, remove decrements them, "present" means all k are non-zero. Cost: a few bytes per slot instead of
 * one bit. (Caveat: remove still assumes the item was actually added — removing something that wasn't can
 * corrupt counts and create false negatives, same as any counting filter.)
 */
public final class CountingBloomFilter<T> {

    private final short[] counters;
    private final int size;
    private final int hashes;

    private CountingBloomFilter(int size, int hashes) {
        this.size = size;
        this.hashes = hashes;
        this.counters = new short[size];
    }

    public static <T> CountingBloomFilter<T> create(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0 || falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("need n > 0 and 0 < fpp < 1");
        }
        int m = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
        int k = Math.max(1, (int) Math.round((double) m / expectedInsertions * Math.log(2)));
        return new CountingBloomFilter<>(m, k);
    }

    public void add(T key) {
        forEachSlot(key, idx -> {
            if (counters[idx] < Short.MAX_VALUE) {
                counters[idx]++;
            }
        });
    }

    public void remove(T key) {
        forEachSlot(key, idx -> {
            if (counters[idx] > 0) {
                counters[idx]--;
            }
        });
    }

    public boolean mightContain(T key) {
        long h = Hashes.hash64(key.toString());
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < hashes; i++) {
            if (counters[index(h1, h2, i)] == 0) {
                return false;
            }
        }
        return true;
    }

    private interface SlotOp {
        void apply(int idx);
    }

    private void forEachSlot(T key, SlotOp op) {
        long h = Hashes.hash64(key.toString());
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < hashes; i++) {
            op.apply(index(h1, h2, i));
        }
    }

    private int index(int h1, int h2, int i) {
        long combined = (h1 & 0xffffffffL) + (long) i * (h2 & 0xffffffffL);
        return (int) Long.remainderUnsigned(combined, size);
    }
}
