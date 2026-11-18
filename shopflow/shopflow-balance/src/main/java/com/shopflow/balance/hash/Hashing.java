package com.shopflow.balance.hash;

/**
 * A stable 64-bit hash (FNV-1a). "Stable" matters: {@link String#hashCode()} is only 32-bit, distributes
 * poorly, and isn't guaranteed across platforms — all bad for a hash ring where the SAME key must land on
 * the SAME position everywhere. FNV-1a is tiny, fast, and spreads well enough for placement (not crypto).
 */
public final class Hashing {

    private Hashing() {}

    public static long hash64(String s) {
        long h = 0xcbf29ce484222325L;          // FNV offset basis
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;                // FNV prime
        }
        return fmix64(h);                      // avalanche the bits
    }

    // MurmurHash3's 64-bit finalizer. FNV alone leaves adjacent inputs with correlated high bits, which
    // clusters ring positions and skews load; fmix64 scrambles every bit so vnodes spread evenly.
    private static long fmix64(long h) {
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
