# 12 · Caching: cache-aside, evict-not-update, fail-open — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `inventory-service/…/product/ProductController.java` (`@Cacheable`), `reservation/ReservationService.java` (`@CacheEvict`), `config/CacheConfig.java` (fail-open, JSON).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's caching design. Read once, then answer.*

Reading a product (`GET /api/v1/products/{sku}`) is far more frequent than changing it, so inventory-service caches it with **cache-aside**: the first read hits Postgres and stores the result in Redis under `key = sku`; later reads are served from Redis. Three deliberate rules shape it.

**1. Cache the DTO, not the entity.** `@Cacheable("products")` stores the `ProductResponse` **record**, not the JPA `@Entity`. The record is immutable, detached from the Hibernate session, and serialises cleanly; caching a managed entity would drag lazy-loading and session state into the cache.

**2. On write, evict — don't update.** When stock changes, the code **evicts** the cached entry rather than writing the new value in. `reserve` does `@CacheEvict(key = "#sku")` (drop that SKU); `release` does `@CacheEvict(allEntries = true)` (the SKU is only known after a lookup, and releases are rare). The reasoning: the stock change is an **atomic SQL `UPDATE`** whose new value the application never loads, so there's nothing to write into the cache — evicting guarantees the next read re-fetches the truth, and a stale quantity is never served for longer than one write.

**3. Stock decisions never read the cache.** The authoritative `decrementStock` runs against Postgres directly; the cache is only for the **read** DTO. So even a stale cache cannot cause overselling.

**Fail-open.** `CacheConfig` implements `CachingConfigurer` and returns a `LoggingCacheErrorHandler`. If Redis is **down**, a cache error is **logged and the request falls through to Postgres** instead of failing — *"a cache must never be a hard dependency of a read path."* Correspondingly, Redis is **excluded from readiness**, so a Redis outage doesn't pull inventory-service pods from the load balancer.

**Serialization.** Redis values are stored as **JSON** (`GenericJackson2JsonRedisSerializer`), not JDK serialization. JDK serialization needs every class to be `Serializable`, breaks on class changes, and is unreadable in `redis-cli`; JSON (with a type hint) avoids all three. A 60-second TTL bounds staleness as a backstop. The `simple` in-memory cache used in tests ignores the Redis-specific config.

---

## Questions

### A — Literal
1. What is cached under `key = sku`, and why the DTO rather than the JPA entity?
2. On a stock change, does the code update the cached value or evict it — and what does `release` evict versus `reserve`?
3. What happens to a `GET /products/{sku}` request when Redis is down?

### B — Inference
4. Why can a **stale** product cache never cause **overselling** in this design?
5. `reserve` evicts a single SKU but `release` evicts **all** entries. From the passage, why the asymmetry?

### C — Trade-off
6. "Evict on write" vs "update the cache on write": what does evicting give up (a tiny cost) and what correctness problem does it avoid?
7. Fail-open means a Redis outage silently shifts all read load onto Postgres. What's the upside, and what's the failure mode you must watch for when the cache is down under heavy traffic?

### D — Transfer / extension
8. A new endpoint caches a **list** of all products (`@Cacheable("product-list")`). A single `reserve` changes one SKU's stock. What must the eviction strategy do now that per-SKU eviction didn't have to, and what's the risk if you forget?
9. The team wants **near-real-time** stock in the read DTO (not just "never stale beyond one write"). Why does caching the *quantity* fight that goal, and what's a cleaner split of what to cache vs what to always read live?

### E — Spot-the-flaw
10. To "make reads even faster," a dev changes `decrementStock`'s service method to **read the cached product first** and compute the new quantity from it, then write. Under a flash sale, what guarantee from topic 05 does this break, and which of the three caching rules did it violate?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. The **`ProductResponse` DTO (a record)**, keyed by SKU. The DTO is used because it's immutable, **detached from the Hibernate session**, and serialises cleanly; a managed JPA entity would carry lazy-loading/session state into the cache and break on serialization.
2. It **evicts** (doesn't update). `reserve` evicts the **single SKU** (`key = "#sku"`); `release` evicts **all entries** (`allEntries = true`).
3. The cache error is **logged and the request falls through to Postgres** (fail-open) — it still returns the product, just without the cache.
</details>

<details>
<summary>B — Inference</summary>

4. Because **stock decisions never read the cache** — the authoritative `decrementStock` runs its atomic conditional `UPDATE` directly against Postgres. The cache only serves the read DTO (for display/browse); the write path re-checks real stock in the DB regardless of what the cache says, so a stale cached quantity can mislead a *display* but can never let a *reservation* oversell.
5. `reserve` already knows the SKU it's reserving, so it can evict precisely that key. `release` only discovers the SKU **after** looking up the reservation (it's handed an `orderRef`, not a SKU) and releases are **rare**, so rather than add a lookup just to target one key, it evicts everything — cheap because it's infrequent. It's a "precise when easy, broad when rare" trade.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Evicting gives up** a little performance: the next read after a write is a guaranteed cache **miss** that must re-fetch from Postgres (a brief latency bump). **It avoids** serving a **wrong/stale value**: if you *updated* the cache, you'd have to compute and write the exact new value — and since the stock change is an atomic SQL `UPDATE` the app never loads back, you'd risk writing a value that doesn't match the DB. Evicting delegates the next value to the source of truth, so the cache can never drift out of sync.
7. **Upside:** availability — a Redis outage degrades performance but **doesn't break reads** (and doesn't cascade via readiness). **Failure mode to watch:** with the cache gone, **every read hits Postgres**, so under heavy traffic the DB can become the bottleneck (connection pool saturation, latency spikes) — a "**thundering herd**" onto the database. You watch DB load/latency and may need to shed load or warm the cache on recovery; fail-open protects correctness, not capacity.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. A single-SKU change now invalidates a **cached aggregate that contains every SKU**, so you must **evict the whole `product-list` entry** (it can't be partially updated) on *any* stock change — not just the per-SKU entry. If you forget, the list cache serves a **stale quantity for the changed SKU** indefinitely (until TTL), even though the per-SKU cache was correctly evicted — an inconsistency between two caches of the same data. List/aggregate caches are much harder to keep coherent, which is itself an argument against caching lists of frequently-changing data.
9. Caching the **quantity** means any cached copy is stale the instant another buyer reserves — and under a flash sale that's constant, so the cache is wrong almost always and eviction churns every write. Cleaner split: cache the **slow-changing, display-only fields** (name, price, description, image) which rarely change, and **always read the live quantity from Postgres** (or expose it via a separate, uncached/stock endpoint). Cache what's stable; never cache the hot, authority-critical number.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. It makes a **stock decision read the cache**, violating rule 3 ("stock decisions never read the cache"). Under a flash sale the cached quantity is stale, so computing "new = cached − qty" and writing it back reintroduces the **lost-update/oversell** race that topic 05's atomic `UPDATE ... WHERE quantity >= qty` was specifically designed to prevent — two buyers both read cached `3`, both write `2`. The authoritative decrement must stay a single atomic SQL statement against the DB; the cache is for reads only. (It also turns a read-only cache into part of the write path, coupling correctness to Redis availability — breaking fail-open too.)
</details>

---

## Tod ke dekho — Local (docker compose + redis)

Goal: see a cache hit/miss, eviction on write, and fail-open when Redis dies.

```bash
make up

# 1) first GET = miss (hits DB), second = hit (from Redis). Check the key exists in Redis:
curl -s http://localhost:8082/api/v1/products/PS5-SLIM | jq
docker compose -f shopflow/docker-compose.yml exec redis redis-cli KEYS 'products*'     # a key for the sku

# 2) EVICT on write: reserve changes stock -> the cached entry is dropped -> next read re-fetches
curl -s -X POST http://localhost:8082/api/v1/reservations -H 'Content-Type: application/json' \
  -d '{"orderRef":"c-1","sku":"PS5-SLIM","quantity":1}' -o /dev/null
docker compose -f shopflow/docker-compose.yml exec redis redis-cli KEYS 'products*'     # PS5 key gone (evicted)
curl -s http://localhost:8082/api/v1/products/PS5-SLIM | jq '.quantity'                  # fresh value from DB

# 3) FAIL-OPEN: kill Redis -> reads still work (fall through to Postgres), readiness stays UP
docker compose -f shopflow/docker-compose.yml stop redis
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/api/v1/products/PS5-SLIM   # 200 (DB fallback)
curl -s http://localhost:8082/actuator/health/readiness | jq                              # UP (Redis excluded)

# 4) values are JSON, readable in redis-cli (not JDK-serialized gibberish):
docker compose -f shopflow/docker-compose.yml start redis; sleep 2
curl -s http://localhost:8082/api/v1/products/PIXEL-9 -o /dev/null
docker compose -f shopflow/docker-compose.yml exec redis redis-cli GET $(docker compose -f shopflow/docker-compose.yml exec redis redis-cli KEYS 'products*' | tr -d '\r' | head -1)
```

**Proved:** cache-aside hit/miss, eviction on reserve, and fail-open reads + readiness UP when Redis is down. Stock stays correct because the decrement never consulted the cache.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — inventory + redis chahiye (compose).

---

## Interview concepts is passage se
- **Cache-aside (lazy loading)** — app reads cache→miss→DB→put.
- **Evict vs update on write** — eviction se cache source-of-truth se drift nahi karta.
- **Cache DTO, not entity** — detached/serializable; session state cache mein nahi.
- **Stock decisions never from cache** — write path DB se, isliye no oversell.
- **Fail-open** — cache read path ki hard dependency nahi; Redis readiness se bahar.
- **JSON vs JDK serialization** — portability/readability.
- **TTL as staleness backstop**; list/aggregate caches harder (coherence).

**Interviewer's lens:** "update kyu nahi, evict kyu" aur "cache down pe kya" (fail-open + thundering-herd risk). Plus cache ko write path se door rakhna (oversell link to topic 05).

> Interview line: *"GET /products cache-aside hai — DTO (record) cache hota, entity nahi. Write pe update nahi, evict karta hoon (reserve: us SKU; release: all, kyunki SKU baad mein pata chalta aur rare hai) — kyunki stock change atomic SQL UPDATE hai jiska naya value app load nahi karta, to evict karke next read truth laata. Stock decision kabhi cache se nahi, isliye stale cache oversell nahi kar sakta. Redis down pe fail-open — log karke Postgres se padho, aur Redis readiness mein nahi. Values JSON mein, JDK serialization nahi."*
</content>
