# shopflow-lock — distributed locking, done safely

"Only one worker should do X at a time" across many processes/pods. The naive `SETNX` lock has sharp
edges; this module implements the safe version — ownership tokens, TTLs, fencing tokens, renewal, and
Redlock — pure Java with the lock logic fully unit-tested (Redis + Lua for the real thing).

> Plain library like `shopflow-outbox` — never boots. Tests use `InMemoryLockStore` (no Redis).

---

## The lock, built up from the naive version
1. **`SETNX key`** — "set if not exists". First writer wins. Problem: if the holder crashes, the lock is
   held **forever**.
2. **`SET key token NX PX ttl`** — add a TTL so a dead holder's lock frees itself. New problem: on expiry
   the lock can be taken by someone else while the original holder is still (slowly) working.
3. **Ownership token** — store a value unique to this acquisition and **release only if it's still yours**
   (atomic check-and-del in Lua). Fixes "my slow `release()` deleted the new owner's lock."
4. **Fencing token** — a number that strictly increases every acquire. The holder passes it to the
   protected resource, which rejects any write with a lower number. This is the ONLY thing that makes a
   lock *correct* when a holder stalls (a GC pause longer than the TTL = two live "holders"). Kleppmann's
   point: the lock alone can't guarantee mutual exclusion under pauses — the resource must fence.
5. **Renewal / watchdog** — for work that may outlast the TTL, `extend()` the lease periodically (only if
   still owner). Stop renewing (crash) → it expires. Keep the TTL short *and* renew, rather than one long TTL.

```
GC pause > TTL, why fencing is needed:
  client A: acquire (fence=33) ── [ 10s STOP-THE-WORLD GC ] ──────► write(fence=33)  ✗ rejected (<34)
  client B:            acquire (fence=34, A's lease expired) ─► write(fence=34)        ✓
  resource remembers "highest fence = 34" and refuses A's stale 33.
```

## Redlock (multi-node)
`RedLock` acquires across N **independent** stores and needs a **majority**. One node down doesn't break
the lock; two clients can't both get a majority. Fail to reach quorum → release the minority you grabbed.
**The debate (know both sides):** Kleppmann argues Redlock isn't safe under clock jumps / long pauses and
you still need fencing; antirez (Redis author) defends it for practical use. Verdict for interviews:
*Redlock for efficiency locks ("mostly don't do the work twice") is fine; for correctness locks, fence at
the resource regardless of the lock.* Per-node fence tokens don't compose into one global counter — a real
caveat; we return the max as best effort.

## Use it
```java
DistributedLock lock = new DistributedLock(new RedisLockStore(stringRedisTemplate));
Optional<LockToken> held = lock.tryAcquire("order:42", Duration.ofSeconds(30));
held.ifPresent(t -> {
    try {
        // ... exclusive work; pass t.fenceToken() to the resource so it can reject a stale holder ...
    } finally {
        lock.release(t);               // owner-checked: safe even if the TTL already lapsed
    }
});

// block up to 2s, retrying ~every 50ms (jittered), each attempt holding a 30s lease:
lock.tryAcquire("order:42", Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofMillis(50));

// multi-node:
new RedLock(List.of(store1, store2, store3)).tryAcquire("order:42", Duration.ofSeconds(30));
```

## Production failure modes handled / named
- **Crashed holder** → TTL auto-releases (never hold forever).
- **Slow/expired holder deleting a new lock** → owner-token check on release/extend (atomic Lua).
- **GC / STW pause > TTL (two live holders)** → fencing tokens at the resource (the lock can't fix this alone).
- **Node failure** → Redlock majority survives one node down.
- **Thundering waiters** → `tryAcquire(..., maxWait, retryEvery)` backs off with jitter.
- **Long work** → renew via `extend()` instead of one giant TTL.

## Where it fits in shopflow
Anywhere exactly-one-runner matters across pods: the `OrderReconciler` runs on every replica today and
leans on optimistic-lock races + idempotency (Walk 53/55) — a distributed lock is the alternative "only
one pod reconciles this batch" approach. Same Redis + atomic-Lua foundation as `shopflow-ratelimit`.

## Tests
`DistributedLockTest` (mutual exclusion, owner-only release, fence monotonicity, expiry-steal via a fake
clock, wait-until-released under concurrency) and `RedLockTest` (majority acquire, contention, minority
release). No infra — in-memory store + deterministic clock.
