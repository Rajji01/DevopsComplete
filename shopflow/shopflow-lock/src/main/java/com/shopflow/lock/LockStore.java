package com.shopflow.lock;

/**
 * The backend that actually holds locks. In-memory ({@link InMemoryLockStore}) for a single node and tests;
 * Redis + Lua in prod. Every method must be ATOMIC — that is the whole game, and why the Redis versions are
 * Lua scripts rather than GET-then-SET from Java.
 */
public interface LockStore {

    /**
     * @param acquired   whether the lock is now held by {@code token}
     * @param fenceToken strictly-increasing per-key counter bumped on each successful acquire ({@code -1}
     *                   when not acquired)
     */
    record AcquireResult(boolean acquired, long fenceToken) {}

    /** Take the lock for {@code token} if it is free or expired (or already ours); set a {@code ttlMillis} expiry. */
    AcquireResult acquire(String key, String token, long ttlMillis);

    /** Release ONLY if {@code token} is the current owner (atomic check-and-delete). */
    boolean release(String key, String token);

    /** Push the expiry out ONLY if {@code token} still owns it — the watchdog/renewal primitive. */
    boolean extend(String key, String token, long ttlMillis);
}
