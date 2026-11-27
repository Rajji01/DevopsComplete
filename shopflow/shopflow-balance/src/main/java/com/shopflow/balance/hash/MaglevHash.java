package com.shopflow.balance.hash;

import java.util.Arrays;
import java.util.List;

/**
 * Maglev hashing (Google, NSDI 2016 — the consistent-hash scheme in Google's L4 load balancer, also in
 * Envoy). It trades a ring's strict "minimal movement" for two things a software L4 balancer needs more:
 * near-PERFECTLY EVEN load and O(1) lookup. It precomputes a fixed lookup table of prime size M; a key is
 * routed by {@code table[hash(key) % M]}, a single array read — no tree walk.
 *
 * <h3>How the table is built</h3>
 * Each backend gets a PERMUTATION of the M slots from two hashes: {@code offset = h1(node) % M} and
 * {@code skip = h2(node) % (M-1) + 1}, giving preference {@code perm[j] = (offset + j*skip) % M}. Because M
 * is prime, {@code skip} is coprime to it, so the permutation visits every slot exactly once. Then backends
 * take turns, each claiming its next not-yet-taken preferred slot, until the table is full. The result is
 * every backend owning ~M/N slots (even load to within one slot), and removing a backend only reshuffles its
 * own slots — about 1/N of the table, the disruption property that matters for connection stickiness.
 *
 * <h3>vs. the other hashers here</h3>
 * {@link HashRing} and {@link RendezvousHash} give strictly minimal movement but uneven load (needs vnodes)
 * and O(log n)/O(n) lookup; {@link BoundedLoadHashRing} caps load but is stateful. Maglev gives even load +
 * O(1) lookup from an immutable table, at the cost of a small rebuild when membership changes — which is
 * exactly the right trade for a stateless L4 balancer that regenerates its table on a backend-set change.
 *
 * <p>Immutable: built once from a fixed backend set; create a new instance when the set changes (as Maglev
 * itself regenerates). Thread-safe to read.
 *
 * @param <N> backend type (placed via {@code toString()})
 */
public final class MaglevHash<N> {

    /** Smallest prime above 2^16 — the common default table size (Envoy's too). */
    public static final int DEFAULT_TABLE_SIZE = 65537;

    private final List<N> backends;
    private final int size;
    private final int[] table; // slot -> index into backends

    public MaglevHash(List<N> backends) {
        this(backends, DEFAULT_TABLE_SIZE);
    }

    public MaglevHash(List<N> backends, int tableSize) {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("need at least one backend");
        }
        if (tableSize <= backends.size() || !isPrime(tableSize)) {
            throw new IllegalArgumentException("tableSize must be a prime greater than the backend count");
        }
        this.backends = List.copyOf(backends);
        this.size = tableSize;
        this.table = populate();
    }

    /** Route a key to a backend: one array read of the precomputed table. */
    public N lookup(String key) {
        int slot = (int) Long.remainderUnsigned(Hashing.hash64(key), size);
        return backends.get(table[slot]);
    }

    public int tableSize() {
        return size;
    }

    // --- Maglev population ---

    private int[] populate() {
        int n = backends.size();
        int[][] permutation = new int[n][size];
        for (int i = 0; i < n; i++) {
            String name = backends.get(i).toString();
            long h1 = Hashing.hash64(name);
            long h2 = Hashing.hash64(name + "\u0000skip"); // a second, independent hash
            int offset = (int) Long.remainderUnsigned(h1, size);
            int skip = (int) Long.remainderUnsigned(h2, size - 1) + 1; // 1..size-1, coprime to prime size
            for (int j = 0; j < size; j++) {
                permutation[i][j] = (int) (((long) offset + (long) j * skip) % size);
            }
        }

        int[] entry = new int[size];
        Arrays.fill(entry, -1);
        int[] next = new int[n]; // each backend's cursor into its preference list
        int filled = 0;
        while (true) {
            for (int i = 0; i < n; i++) {
                int candidate = permutation[i][next[i]];
                while (entry[candidate] >= 0) {     // slot taken -> advance this backend's cursor
                    next[i]++;
                    candidate = permutation[i][next[i]];
                }
                entry[candidate] = i;
                next[i]++;
                filled++;
                if (filled == size) {
                    return entry;
                }
            }
        }
    }

    private static boolean isPrime(int x) {
        if (x < 2) {
            return false;
        }
        if (x % 2 == 0) {
            return x == 2;
        }
        for (int d = 3; (long) d * d <= x; d += 2) {
            if (x % d == 0) {
                return false;
            }
        }
        return true;
    }
}
