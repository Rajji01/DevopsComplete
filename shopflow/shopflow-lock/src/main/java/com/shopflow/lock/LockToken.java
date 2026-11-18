package com.shopflow.lock;

/**
 * Proof that you hold a lock — and the key to releasing it SAFELY. Two fields matter beyond the obvious:
 *
 *  • {@code token} — a value unique to THIS acquisition (a UUID). Release/extend must check it, so you can
 *    only release the lock YOU took. Without it, lock A expires, B acquires, then A's slow {@code release()}
 *    deletes B's lock — a classic double-ownership bug.
 *  • {@code fenceToken} — a number that strictly INCREASES on every successful acquire of the key. The
 *    protected resource records the highest it has seen and REJECTS any write carrying a lower one. This is
 *    the only thing that actually makes a lock safe against a stalled holder (a GC pause longer than the TTL
 *    lets two clients believe they hold the lock; the fence token lets the resource tell them apart —
 *    Kleppmann's "How to do distributed locking").
 */
public record LockToken(String key, String token, long fenceToken, long expiresAtMillis) {
}
