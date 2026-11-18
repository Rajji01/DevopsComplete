package com.shopflow.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * A mutual-exclusion lock over a {@link LockStore}. Always acquired with a TTL so a crashed holder's lock
 * frees itself (no lock held forever by a dead process). Three usage shapes:
 *   tryAcquire(key, ttl)                      — one shot, non-blocking
 *   tryAcquire(key, ttl, maxWait, retryEvery) — spin with jittered backoff up to maxWait
 *   extend(token, ttl)                        — renew while you still hold it (watchdog primitive)
 *
 * The returned {@link LockToken} is how you release/extend safely (ownership check) and fence writes to the
 * protected resource (see {@link LockToken#fenceToken()}).
 */
public final class DistributedLock {

    private final LockStore store;
    private final Supplier<String> tokenGenerator;

    public DistributedLock(LockStore store) {
        this(store, () -> UUID.randomUUID().toString());
    }

    public DistributedLock(LockStore store, Supplier<String> tokenGenerator) {
        this.store = store;
        this.tokenGenerator = tokenGenerator;
    }

    public Optional<LockToken> tryAcquire(String key, Duration ttl) {
        String token = tokenGenerator.get();
        LockStore.AcquireResult r = store.acquire(key, token, ttl.toMillis());
        if (!r.acquired()) {
            return Optional.empty();
        }
        return Optional.of(new LockToken(key, token, r.fenceToken(), System.currentTimeMillis() + ttl.toMillis()));
    }

    /** Block (spinning with jittered backoff) until the lock is free or {@code maxWait} passes. */
    public Optional<LockToken> tryAcquire(String key, Duration ttl, Duration maxWait, Duration retryEvery) {
        long deadline = System.currentTimeMillis() + maxWait.toMillis();
        while (true) {
            Optional<LockToken> got = tryAcquire(key, ttl);
            if (got.isPresent()) {
                return got;
            }
            if (System.currentTimeMillis() >= deadline) {
                return Optional.empty();
            }
            // jitter the retry so a crowd of waiters doesn't retry in lockstep and thunder the store
            long base = retryEvery.toMillis();
            long sleep = base <= 0 ? 0 : base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    public boolean release(LockToken token) {
        return store.release(token.key(), token.token());
    }

    public boolean extend(LockToken token, Duration ttl) {
        return store.extend(token.key(), token.token(), ttl.toMillis());
    }
}
