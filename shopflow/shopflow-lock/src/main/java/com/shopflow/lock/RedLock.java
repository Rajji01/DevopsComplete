package com.shopflow.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    private final List<LockStore> stores;
    private final Supplier<String> tokenGenerator;

    public RedLock(List<LockStore> stores) {
        this(stores, () -> UUID.randomUUID().toString());
    }

    public RedLock(List<LockStore> stores, Supplier<String> tokenGenerator) {
        if (stores.isEmpty()) {
            throw new IllegalArgumentException("Redlock needs at least one store");
        }
        this.stores = List.copyOf(stores);
        this.tokenGenerator = tokenGenerator;
    }

    private int quorum() {
        return stores.size() / 2 + 1;
    }

    public Optional<LockToken> tryAcquire(String key, Duration ttl) {
        String token = tokenGenerator.get();
        List<LockStore> held = new ArrayList<>();
        long maxFence = -1;
        for (LockStore store : stores) {
            LockStore.AcquireResult r = store.acquire(key, token, ttl.toMillis());
            if (r.acquired()) {
                held.add(store);
                maxFence = Math.max(maxFence, r.fenceToken());
            }
        }
        if (held.size() >= quorum()) {
            return Optional.of(new LockToken(key, token, maxFence, System.currentTimeMillis() + ttl.toMillis()));
        }
        for (LockStore store : held) {
            store.release(key, token); // didn't reach a majority — don't leave a minority locked
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
