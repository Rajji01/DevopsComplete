package com.shopflow.cache;

/**
 * What a write does to the cache + the system of record (SoR). Configurable via {@code cache.write-policy}.
 *
 *  WRITE_THROUGH — write SoR, then cache, synchronously. Cache is always consistent with the SoR; the
 *                  write pays the SoR latency. Safe default.
 *  WRITE_BEHIND  — write cache now, flush to the SoR asynchronously (batched). Fast writes, but a crash
 *                  before flush LOSES data — only for tolerant data, and a real one needs a durable queue.
 *  WRITE_AROUND  — write SoR only, do NOT populate the cache. Best when freshly written data is rarely
 *                  read soon (avoids polluting the cache with cold entries).
 *  INVALIDATE    — write SoR, then DROP the cached entry so the next read reloads. The classic cache-aside
 *                  write; avoids serving a stale value without having to recompute it on the write path.
 *
 * (In this simple cache WRITE_AROUND and INVALIDATE do the same thing — write SoR, don't keep a cached
 * value — but the intent differs, so both names exist.)
 */
public enum WritePolicy {
    WRITE_THROUGH,
    WRITE_BEHIND,
    WRITE_AROUND,
    INVALIDATE
}
