package com.shopflow.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Redlock: a lock across N INDEPENDENT stores (think N separate Redis masters, not replicas). You hold the
 * lock only if a MAJORITY grant it — so a single node failing doesn't take the lock down, and two clients
 * can't both win (they can't both get the majority). Fail to get the majority → release the minority you did
 * get, so you never sit on a partial lock.
 *
 * Honest caveat (the famous Kleppmann vs antirez debate): Redlock's safety under GC pauses / clock jumps is
 * disputed, and per-node fence tokens don't compose into one global monotonic token (we return the max as a
 * best effort). For correctness-critical locking, pair ANY lock with fencing tokens at the resource; for
 * "mostly avoid duplicate work" efficiency locking, Redlock is fine. Use it knowing which you need.
 */
public final class RedLock {

    // antirez's Redlock: when we work out how much validity is left, allow for the clocks on the N nodes
    // drifting relative to each other — 1% of the TTL plus a couple of ms, so a lock never looks valid for
    // longer than it safely is.
    private static final double CLOCK_DRIFT_FACTOR = 0.01;

    private final List<LockStore> stores;
    private final Supplier<String> tokenGenerator;
    private final LongSupplier clock;

    public RedLock(List<LockStore> stores) {
        this(stores, () -> UUID.randomUUID().toString(), System::currentTimeMillis);
    }

    public RedLock(List<LockStore> stores, Supplier<String> tokenGenerator) {
        this(stores, tokenGenerator, System::currentTimeMillis);
    }

    public RedLock(List<LockStore> stores, Supplier<String> tokenGenerator, LongSupplier clock) {
        if (stores.isEmpty()) {
            throw new IllegalArgumentException("Redlock needs at least one store");
        }
        this.stores = List.copyOf(stores);
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    private int quorum() {
        return stores.size() / 2 + 1;
    }

    public Optional<LockToken> tryAcquire(String key, Duration ttl) {
        String token = tokenGenerator.get();
        long ttlMillis = ttl.toMillis();
        long start = clock.getAsLong();
        List<LockStore> held = new ArrayList<>();
        long maxFence = -1;
        for (LockStore store : stores) {
            LockStore.AcquireResult r = store.acquire(key, token, ttlMillis);
            if (r.acquired()) {
                held.add(store);
                maxFence = Math.max(maxFence, r.fenceToken());
            }
        }
        // Redlock steps 3–5: a majority is NOT enough on its own. The TTL started counting down on the first
        // node the instant we locked it, not now, so the real remaining validity is the TTL minus the time we
        // spent acquiring (minus the drift allowance). If reaching the quorum ate the whole TTL, the lock may
        // already have expired on the earliest node while we were still acquiring — another client could hold
        // it too — so this acquisition has FAILED even though a majority granted it: release everything.
        long drift = (long) (ttlMillis * CLOCK_DRIFT_FACTOR) + 2;
        long validity = ttlMillis - (clock.getAsLong() - start) - drift;
        if (held.size() >= quorum() && validity > 0) {
            // expiry is measured from when acquisition STARTED (when the first node's TTL began), not now,
            // so the holder never trusts the lock for longer than it is actually safe.
            return Optional.of(new LockToken(key, token, maxFence, start + ttlMillis - drift));
        }
        for (LockStore store : held) {
            store.release(key, token); // no majority, or no validity left — don't leave a partial lock
        }
        return Optional.empty();
    }

    public boolean release(LockToken token) {
        boolean releasedAny = false;
        for (LockStore store : stores) {
            releasedAny |= store.release(token.key(), token.token());
        }
        return releasedAny;
    }
}
