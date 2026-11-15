package com.shopflow.lock;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Single-JVM lock store — correct across threads, used for tests and as the local fallback. The clock is
 * injected so tests can expire a lock without sleeping. (This does NOT give you a cross-process lock; that
 * is what the Redis store is for. It exists so the lock LOGIC — ownership, expiry, fencing — is testable.)
 *
 * All methods are {@code synchronized}: acquire must read-the-owner and write-the-owner as one step, exactly
 * like the Redis version is one Lua script.
 */
public final class InMemoryLockStore implements LockStore {

    private record Held(String token, long expiresAt) {}

    private final Map<String, Held> locks = new HashMap<>();
    private final Map<String, Long> fences = new HashMap<>();
    private final LongSupplier clock;

    public InMemoryLockStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public synchronized AcquireResult acquire(String key, String token, long ttlMillis) {
        long now = clock.getAsLong();
        Held h = locks.get(key);
        boolean free = (h == null) || (h.expiresAt() <= now) || h.token().equals(token);
        if (!free) {
            return new AcquireResult(false, -1);
        }
        locks.put(key, new Held(token, now + ttlMillis));
        long fence = fences.merge(key, 1L, Long::sum); // strictly increases on every successful acquire
        return new AcquireResult(true, fence);
    }

    @Override
    public synchronized boolean release(String key, String token) {
        Held h = locks.get(key);
        if (h != null && h.token().equals(token)) {
            locks.remove(key);
            return true;
        }
        return false; // not ours (or already gone) — must not delete someone else's lock
    }

    @Override
    public synchronized boolean extend(String key, String token, long ttlMillis) {
        long now = clock.getAsLong();
        Held h = locks.get(key);
        if (h != null && h.token().equals(token) && h.expiresAt() > now) {
            locks.put(key, new Held(token, now + ttlMillis));
            return true;
        }
        return false; // can't extend a lock we don't own or one that already expired
    }
}
