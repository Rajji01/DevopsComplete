# 12 · Caching (cache-aside, evict, fail-open) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `product/ProductController.java`, `reservation/ReservationService.java`, `config/CacheConfig.java`.

## Kya (one line)
Product read ko **cache-aside** se Redis mein cache karo (DTO, entity nahi); write pe **evict karo, update nahi**; stock decision **kabhi cache se nahi** (isliye no oversell); aur Redis down pe **fail-open** (DB se padho) — cache kabhi read-path ki hard dependency nahi.

## Easy way (Hinglish analogy)
Dukaan ke bahar ek **blackboard** hai jahan aaj ka rate likha (cache). Grahak rate poochhe to pehle blackboard dekho; nahi likha to andar jaake (DB) dekh ke board pe likh do. **Rate badle** to board pe naya likhne ke bajaye **mita do** — agli baar koi poochhega to fresh andar se laake likhoge (galat na likh jaaye). **Asli sauda** (paisa/stock) kabhi board se nahi, hamesha andar register se — board purana ho sakta. Board gir jaye (Redis down) to koi dikkat nahi, seedha andar se batao (fail-open), dukaan band nahi hoti.

## Kyu (ye problem solve karta hai)
- Read >> write, to cache latency/DB-load bachata.
- **Evict vs update**: stock change ek atomic SQL `UPDATE` hai jiska naya value app load hi nahi karta — update karne jao to galat/stale value likh sakte. Evict se next read source-of-truth se aata; cache drift nahi karta.
- **Cache write path mein nahi**: warna stale cache se oversell (topic 05 ka race wapas).
- **Fail-open**: cache ko hard dependency banao to Redis blip = read outage + readiness fail (cascade). Fail-open availability bachata.

## Kaise (step by step)
1. **Read** (`ProductController.get`): `@Cacheable("products", key="#sku")` → miss pe DB, `ProductResponse` (record) store.
2. **Write evict** (`ReservationService`): `reserve` → `@CacheEvict(key="#sku")`; `release` → `@CacheEvict(allEntries=true)`.
3. **Authority**: `decrementStock` seedha Postgres (atomic), cache ko nahi chhuta.
4. **CacheConfig**: `implements CachingConfigurer`, `errorHandler()=LoggingCacheErrorHandler` → fail-open. `GenericJackson2JsonRedisSerializer` → JSON values. Redis-specific config sirf `cache.type=redis` pe; tests ka `simple` cache ignore karta.
5. **Backstop**: 60s TTL. Redis readiness se bahar.

## Flow diagram (read + write)
```
 GET /products/{sku}
     │ @Cacheable("products", key=sku)
     ├─ HIT  → return from Redis
     └─ MISS → DB findBySku → store DTO → return
                                   ▲ (Redis down? LoggingCacheErrorHandler: log + go to DB = FAIL-OPEN)

 reserve(sku)  → atomic decrementStock (DB, NOT cache) → @CacheEvict(key=sku)
 release(ref)  → increment (DB)                        → @CacheEvict(allEntries=true)
                                   │
                           next GET = MISS → fresh from DB  (never stale > 1 write)
```

## What-to-cache diagram
```
 slow-changing, display-only (name, price, desc)  → CACHE (safe, high hit rate)
 hot, authority-critical (quantity / stock)        → ALWAYS LIVE from DB (never decide on cache)
 write happens                                      → EVICT (let next read re-fetch truth)
 cache down                                         → FAIL-OPEN to DB (not a hard dependency)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Cache-aside + evict** *(ShopFlow)* | lazy load, evict on write | simple, no drift | write ke baad ek miss | read-heavy |
| **Write-through** | write cache+DB together | cache always warm | write slower, couples write to cache | read-after-write hot |
| **Write-behind** | cache now, DB async | fast writes | loss risk, complexity | high write throughput |
| **Update cache on write** | compute+put new value | no post-write miss | drift risk, must know value | derivable values |
| **Fail-closed cache** | cache down → error | strict | **cache = hard dependency** | kabhi nahi (read path) |

## Understanding check
1. Cache DTO kyu, entity kyu nahi?
2. Write pe evict kyu, update kyu nahi?
3. Stale cache oversell kyu nahi kar sakta?
4. Redis down pe kya hota, aur readiness mein kyu nahi?
5. `release` all-entries evict, `reserve` single — kyu?

## Interview mein ye bolo
*"GET /products cache-aside hai — DTO (record) cache hota, JPA entity nahi (detached + serializable). Write pe update nahi, evict karta hoon (reserve us SKU, release all — SKU baad mein pata chalta aur rare hai) kyunki stock change atomic SQL UPDATE hai jiska naya value app load nahi karta; evict se next read DB se fresh laata, cache drift nahi karta. Stock decision kabhi cache se nahi — isliye stale cache oversell nahi kara sakta. Redis down pe fail-open (LoggingCacheErrorHandler → DB fallback), aur Redis readiness mein nahi, to outage cascade nahi karta. Values JSON mein store (JDK serialization nahi). Watch: cache down pe saara load DB pe — thundering herd."*

> Related: [`../05-overselling-concurrency/`](../05-overselling-concurrency/) (stock from DB, not cache), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (Redis excluded from readiness).
</content>
