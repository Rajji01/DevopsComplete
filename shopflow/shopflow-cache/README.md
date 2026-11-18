# shopflow-cache — caching techniques, as a small tested library

A cache answers "can I avoid recomputing / re-fetching this?" The hard parts aren't the hash map — they're
**what to evict**, **how reads and writes relate to the system of record (SoR)**, and **what happens under
concurrency and misses**. This module implements each as a swappable technique, pure Java, fully unit-tested.

> Like `shopflow-outbox`, this is a plain library — it never boots a Spring context, so it has no DB/Redis
> dependency and the tests need no infra. Wire it into a service by handing `CacheFactory` a loader/writer.

---

## 1. Eviction — what to drop when full (`cache.eviction`)
| Policy | Drops | Good when | Flaw |
|---|---|---|---|
| **LRU** | least recently used | temporal locality (recent = soon again) | a scan of many one-off keys flushes the hot set |
| **LFU** | least frequently used | a few perennially-hot keys | needs frequency tracking; a stale hot key can linger (real systems use *windowed* TinyLFU — Caffeine) |
| **FIFO** | oldest inserted | almost never | evicts a hot key just for being old |
Plus **TTL**: every entry can expire by time (lazy — checked on read).

## 2. Read strategies
- **Cache-aside** (lazy): the *app* checks the cache, and on a miss loads from the SoR and populates it.
  Building block: `getIfPresent()` + your own load + `put()`. Most common; the cache stays dumb.
- **Read-through**: the *cache* owns the loader and fetches on a miss. Here that's `get(key)` — one call,
  the cache does the rest. Cleaner call sites; the cache must know how to load.

## 3. Write strategies (`cache.write-policy`)
| Policy | Does | Trade-off |
|---|---|---|
| **write-through** | write SoR, then cache | always consistent; write pays SoR latency |
| **write-behind** | cache now, flush to SoR async | fast writes; **crash before flush loses data** (needs a durable queue in prod) |
| **write-around** | write SoR only, skip cache | avoids caching data that won't be read soon |
| **invalidate** | write SoR, drop cached entry | cache-aside write; next read reloads fresh (no stale serve) |

## 4. The three cache disasters, and the defences here
- **Stampede / thundering herd** — a hot key expires, N requests all miss and hit the DB at once.
  → **single-flight**: the first caller loads, the rest await the same in-flight future (one DB hit, not N).
- **Penetration** — lookups for keys that don't exist bypass the cache every time.
  → **negative caching**: cache the "not found" for a short TTL so those misses are absorbed too.
- **Avalanche** — many keys expire at the same instant → a synchronized flood.
  → **TTL jitter**: each TTL gets a small random spread so expiries scatter.

```
single-flight:                        negative caching:
  t0  A misses "x" ─ loads (DB) ...      get("ghost") → DB → null → cache null (5s)
  t0  B misses "x" ─ waits on A          get("ghost") → cache hit (null) → no DB
  t0  C misses "x" ─ waits on A          ... repeated misses cost nothing for 5s
  t1  A done → B,C get A's value
```

---

## Use it
```java
// read-through, LRU, 60s TTL (defaults), DB read = productRepo::findJson
LoadingCache<String, String> cache = CacheFactory.readThrough(CacheProperties.defaults(), productRepo::findJson);
String v = cache.get("SKU-1").orElse(null);          // loads once, then served from cache

// write-through with a writer, custom policy
var props = new CacheProperties(10_000, EvictionType.LFU, WritePolicy.WRITE_THROUGH, 30_000, 0.1, 2_000);
var cache2 = CacheFactory.create(props, System::currentTimeMillis, productRepo::findJson, productRepo::save, exec);
cache2.put("SKU-1", json);                            // SoR + cache, consistent
```

## Config knobs (`CacheProperties`)
`capacity` · `eviction` (LRU/LFU/FIFO) · `writePolicy` · `ttlMillis` · `jitterFraction` (avalanche spread) ·
`negativeTtlMillis` (penetration defence; 0 = off).

## Tests
`EvictionPolicyTest` (LRU/LFU/FIFO victim choice), `BoundedLocalCacheTest` (capacity eviction + TTL via a
fake clock + stats), `LoadingCacheTest` (read-through, **single-flight under 8 concurrent callers = 1 load**,
negative caching expiry, and every write policy). No infra — pure logic, deterministic clock.

## Distributed (prod) note
Swap the in-memory store for a Redis-backed `CacheStore` (SET with PX for TTL, GET, DEL) and the same
strategy layer works across pods. Two things change: single-flight becomes *per-pod* unless you add a short
distributed lock (the same Lua-lock idea as `shopflow-ratelimit`), and eviction/TTL are Redis's job
(`maxmemory` + `allkeys-lru` + per-key PX). In ShopFlow, inventory-service already caches product reads in
Redis with a fail-open handler (Walk 90/91) — this module is the reusable, techniques-complete version of
that idea, with the stampede/penetration/avalanche defences made explicit.
