package com.shopflow.sketch;

/**
 * Count-Min sketch: estimate "how many times have I seen this key?" over a huge stream in fixed, tiny space
 * (a {@code depth × width} grid of counters). Each key hashes to one cell per row and adds there; the
 * estimate is the MINIMUM across its rows. Collisions can only ADD to a cell, so the sketch NEVER
 * underestimates — and taking the min discards the rows where this key collided with a heavy one. Used for
 * heavy-hitters / "top-K", DDoS source detection, and approximate frequency in query planners.
 *
 * Sizing: width = ⌈e/ε⌉ bounds the over-estimate to ε·(total count); depth = ⌈ln(1/δ)⌉ makes that hold with
 * probability 1−δ. Smaller ε/δ = more memory, tighter answers.
 */
public final class CountMinSketch {

    private final long[][] table;
    private final int width;
    private final int depth;

    private CountMinSketch(int width, int depth) {
        this.width = width;
        this.depth = depth;
        this.table = new long[depth][width];
    }

    public static CountMinSketch create(double epsilon, double delta) {
        if (epsilon <= 0 || epsilon >= 1 || delta <= 0 || delta >= 1) {
            throw new IllegalArgumentException("need 0 < epsilon < 1 and 0 < delta < 1");
        }
        int w = (int) Math.ceil(Math.E / epsilon);
        int d = Math.max(1, (int) Math.ceil(Math.log(1.0 / delta)));
        return new CountMinSketch(w, d);
    }

    public void add(String key, long count) {
        long h = Hashes.hash64(key);
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int r = 0; r < depth; r++) {
            table[r][column(h1, h2, r)] += count;
        }
    }

    public long estimate(String key) {
        long h = Hashes.hash64(key);
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        long min = Long.MAX_VALUE;
        for (int r = 0; r < depth; r++) {
            min = Math.min(min, table[r][column(h1, h2, r)]);
        }
        return min;
    }

    private int column(int h1, int h2, int r) {
        long combined = (h1 & 0xffffffffL) + (long) r * (h2 & 0xffffffffL);
        return (int) Long.remainderUnsigned(combined, width);
    }
}
