package com.shopflow.sketch;

/**
 * 64-bit hash (FNV-1a + MurmurHash3 fmix finalizer) that these structures split into two 32-bit halves for
 * "double hashing": the i-th hash is {@code h1 + i*h2}. Kirsch–Mitzenmacher showed this gives the same
 * false-positive behaviour as i independent hashes — so we compute ONE hash and derive k from it, cheaply.
 */
final class Hashes {

    private Hashes() {}

    static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }
}
