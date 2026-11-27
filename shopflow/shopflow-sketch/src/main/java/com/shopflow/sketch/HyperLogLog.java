package com.shopflow.sketch;

/**
 * HyperLogLog: estimate "how many DISTINCT items have I seen?" over a huge stream in a few KB, instead of
 * a {@code HashSet} that grows with the cardinality. The classic use is unique-visitor / distinct-key counts
 * (distinct cards, distinct accounts touched, distinct IPs) where an exact count would cost gigabytes but a
 * ~2% estimate is plenty — and where you want to MERGE partial counts from many shards without shipping the
 * raw ids.
 *
 * <h3>How it works (the intuition)</h3>
 * Hash each item to 64 bits. The first {@code precision} bits pick one of {@code m = 2^precision} registers;
 * the rest is a random bit pattern, and we record ρ = (position of the leftmost 1-bit) in it. A long run of
 * leading zeros is rare, so the LONGEST run seen in a register is evidence of how many distinct items landed
 * there — seeing a run of k leading zeros suggests ~2^k distinct items. Each register keeps the MAX ρ it has
 * seen (so re-adding the same item is a no-op — idempotent, unlike a naive counter), and the harmonic mean of
 * {@code 2^register} across all m registers, scaled by a bias constant, estimates the cardinality. Averaging
 * over m registers cuts the variance: the standard error is ≈ {@code 1.04 / sqrt(m)}.
 *
 * <h3>Corrections</h3>
 * The raw estimator is biased when the set is small relative to m (many registers still zero). When the
 * estimate is small and some registers are untouched we switch to LINEAR COUNTING ({@code m·ln(m/V)}, V =
 * number of zero registers) — exactly the "balls in bins, count the empty bins" trick, which is accurate in
 * that regime. We hash to 64 bits, so the original paper's large-cardinality correction (a 32-bit-hash
 * artefact) is not needed and is deliberately omitted — later analysis showed it only adds error.
 *
 * <h3>Mergeable</h3>
 * Two HyperLogLogs of the same precision combine by taking the element-wise MAX of their registers
 * ({@link #mergeFrom}). That is the whole reason to prefer it over an exact set in a distributed system:
 * each shard keeps its own sketch over its slice of the stream, and the union's distinct count is the merged
 * sketch — no central set, no shipping ids, and the merge is associative + commutative + idempotent.
 *
 * <p>Not thread-safe: one writer per instance, or guard externally. (Registers are a plain {@code byte[]};
 * {@code add} does a read-max-write that two threads could race.)
 */
public final class HyperLogLog {

    private final int precision;   // b: number of hash bits used to index a register
    private final int m;           // number of registers = 2^precision
    private final byte[] registers; // each holds the max ρ seen (0..64, fits a byte)

    private HyperLogLog(int precision) {
        this.precision = precision;
        this.m = 1 << precision;
        this.registers = new byte[m];
    }

    /**
     * @param precision register-index bits (4..16). More precision = more registers = tighter estimate and
     *                  more memory: m = 2^precision bytes, standard error ≈ 1.04/sqrt(m). precision 14
     *                  (16384 registers, 16 KB) gives ≈ 0.8% error — the common default (Redis uses 14).
     */
    public static HyperLogLog create(int precision) {
        if (precision < 4 || precision > 16) {
            throw new IllegalArgumentException("precision must be in [4, 16], got: " + precision);
        }
        return new HyperLogLog(precision);
    }

    public void add(String item) {
        long x = Hashes.hash64(item);
        int index = (int) (x >>> (64 - precision));      // top `precision` bits choose the register
        // ρ = leftmost-1 position (count of leading zeros + 1) over the REMAINING bits. Shift the index
        // bits out, then OR a sentinel 1 at the lowest surviving position so an all-zero remainder caps ρ
        // at (64 - precision + 1) instead of running into the shifted-in zeros.
        int rho = Long.numberOfLeadingZeros((x << precision) | (1L << (precision - 1))) + 1;
        if (rho > registers[index]) {
            registers[index] = (byte) rho;
        }
    }

    /** The estimated number of distinct items added so far. */
    public long cardinality() {
        double sum = 0.0;
        int zeros = 0;
        for (byte register : registers) {
            sum += 1.0 / (1L << register);  // 2^(-register)
            if (register == 0) {
                zeros++;
            }
        }
        double estimate = alpha() * m * m / sum;

        // small-range: the raw estimator is biased low here; linear counting is accurate while registers
        // are still empty. (Only when at least one register is zero — otherwise ln(m/0) blows up.)
        if (estimate <= 2.5 * m && zeros > 0) {
            estimate = m * Math.log((double) m / zeros);
        }
        return Math.round(estimate);
    }

    /**
     * Fold {@code other} into this sketch: register[i] = max(this[i], other[i]). After this, {@code this}
     * estimates the distinct count of the UNION of both streams. Both must share the same precision.
     */
    public void mergeFrom(HyperLogLog other) {
        if (other.precision != precision) {
            throw new IllegalArgumentException(
                    "cannot merge precision " + other.precision + " into " + precision);
        }
        for (int i = 0; i < m; i++) {
            if (other.registers[i] > registers[i]) {
                registers[i] = other.registers[i];
            }
        }
    }

    /** The relative standard error this precision guarantees (≈ 1.04/sqrt(m)); ~68% of estimates fall within it. */
    public double standardError() {
        return 1.04 / Math.sqrt(m);
    }

    /** The bias-correction constant α_m (Flajolet et al.). */
    private double alpha() {
        return switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1.0 + 1.079 / m);
        };
    }
}
