# Redis — interview drill set (ShopFlow edition)

> **Ye kya hai:** ek **question-only drill sheet** — basic se prod-level failures tak. Redis ke study notes `../11-security-caching-performance.md` mein hain; ye wo set hai jise **ek-ek karke mock-interview** ki tarah drill karna hai: question padho, 2–4 sentence mein **bolo**, phir notes se check karo.
>
> **Tags:** `[C]` = core concept · `[SF]` = ShopFlow se directly defend kar sakta hai · `[P]` = production / failure scenario.
>
> **Honesty note:** ShopFlow mein Redis **sirf caching** ke liye hai (inventory-service product reads). Distributed lock / Redis rate-limiter / pub-sub / streams abhi project mein **nahi** hai — un sawaalon ko "concept jaanta hoon, next step hota" ki tarah honestly answer karna, jhoot "mera project karta hai" mat bolna.

## ShopFlow mein Redis kahan (grounding — taaki [SF] sach rahe)

- **Use:** `inventory-service` ke **product reads** cached — `GET /products/{sku}` pe `@Cacheable("products")` (cache-aside).
- **Config:** `CacheConfig.java` — JSON values (`GenericJackson2JsonRedisSerializer`, JDK serialization nahi), sirf tab jab `spring.cache.type=redis` (local/test mein `simple` in-memory cache).
- **Fail-open:** `CachingConfigurer` + `LoggingCacheErrorHandler` — **Redis down → cache miss log hota hai, read Postgres se chala jaata** (kuch tootta nahi). Readiness probe mein Redis **shaamil nahi**.
- **Eviction:** compose mein `redis:7.4-alpine --maxmemory 64mb --maxmemory-policy allkeys-lru`, cache TTL **60s**.
- **Invalidation:** `reserve` us SKU ko evict karta, `release` saara evict karta. **Stock ka faisla kabhi cache se nahi hota** — hamesha DB (correctness > speed).
- **Rate limiting:** abhi `resilience4j` **in-memory, per-pod** (`@RateLimiter("orders")`) — Redis-backed global limiter abhi nahi, "next step".

---

## TIER 1 — Fundamentals (warm-up)

1. Redis kya hai? Ek line mein kyun itna fast (in-memory, single-threaded event loop)? `[C]`
2. Redis **single-threaded** hai phir bhi itna tez kaise? Iska matlab multi-core use nahi karta? `[C]`
3. Cache kyun chahiye? ShopFlow mein kya cache kiya aur kyun? `[SF][C]`
4. Redis vs Memcached — kab kaun? `[C]`
5. Redis sirf cache hai ya database bhi ho sakta hai? Persistence hoti hai? `[C]`
6. **TTL** kya hai? ShopFlow mein product cache ki TTL kitni aur kyun? `[SF]`
7. Key naming / namespacing production mein kaise karte ho (e.g. `products::SKU123`)? `[C]`

## TIER 2 — Data structures (Redis ki asli taaqat)

8. Redis ki core data structures ginao (String, Hash, List, Set, Sorted Set) — har ek ka ek real use-case. `[C]`
9. **Sorted Set (ZSET)** kahan? (leaderboard, rate-limiter window, delayed queue) `[C][P]`
10. **Hash** kab String-of-JSON se behtar? `[C]`
11. Counter / atomic increment — `INCR` ka use-case? (views, rate-limit count) `[C]`
12. `SETNX` / `SET key val NX EX` kis kaam aata hai? (lock ka base) `[C][P]`
13. HyperLogLog / Bitmap kab? (unique counts, daily-active flags) `[C]`

## TIER 3 — Caching patterns (interview ka dil)

14. **Cache-aside (lazy loading)** pattern samjhao — ShopFlow kaun sa use karta? `[SF][C]`
15. Cache-aside vs **write-through** vs **write-behind** — teeno ka farq aur trade-off? `[C]`
16. **Cache invalidation** — "two hard things in CS" wala. ShopFlow mein reserve/release pe kaise evict hota? `[SF][P]`
17. **Stale data** — cache mein purana price, DB mein naya. Kaise handle? TTL vs explicit evict? `[SF][P]`
18. ShopFlow stock-count cache mein kyun **nahi** rakhta, sirf product-info rakhta? (correctness vs speed) `[SF][P]`
19. Cache **hit ratio** kaise measure aur improve karte ho? `[P]`

## TIER 4 — Eviction, memory & persistence

20. `maxmemory` bhar gaya — Redis kya karta? Eviction policies ginao. `[C][P]`
21. **`allkeys-lru`** (ShopFlow ka setting) kya karta, aur `volatile-lru` / `allkeys-lfu` se kaise alag? `[SF][C]`
22. LRU vs LFU — kab kaun behtar? `[C]`
23. **RDB snapshot** vs **AOF** persistence — dono ka farq, durability vs performance? `[C][P]`
24. Redis restart hua — data bacha ki gaya? ShopFlow ke cache ke liye farak padta hai? `[SF][P]`
25. **Big keys / hot keys** kya hain aur production mein kyun khatarnak? Kaise detect? `[P]`

## TIER 5 — High availability & scaling

26. Redis **replication** (master-replica) kaise kaam karta? Read scaling? `[C][P]`
27. **Redis Sentinel** kya karta — automatic failover kaise? `[P]`
28. **Redis Cluster** (sharding) kab chahiye? Hash slots kya hain? `[P]`
29. Single Redis node down ho gaya production mein — blast radius kya? ShopFlow mein kya hota (fail-open)? `[SF][P]`
30. Replica lag — master pe likha, replica se padha, purana mila. Kaise handle? `[P]`

## TIER 6 — Distributed patterns (concept — ShopFlow mein abhi nahi, honestly)

31. **Distributed lock** Redis se kaise banate (`SET NX EX`)? Ise kis problem ke liye use karoge? `[C][P]`
32. **Redlock** algorithm kya hai aur iski criticism kya hai (clock, GC pause)? `[P]`
33. Lock le liya par process crash ho gaya release se pehle — deadlock se kaise bachte (TTL + token)? `[P]`
34. **Rate limiting Redis se** — fixed window vs sliding window vs token bucket, kaun sa ZSET/INCR se? (ShopFlow abhi in-memory per-pod — iska limitation kya?) `[SF][P]`
35. **Idempotency key store** Redis mein rakhna — pros/cons vs DB table (ShopFlow `processed_event` DB mein kyun hai Redis mein nahi)? `[SF][P]`
36. Redis **Pub/Sub** vs **Streams** — Kafka se kab alag/kam? `[C]`

## TIER 7 — Production failures & scenarios (yahan jeet ya haar)

37. **Cache stampede / thundering herd** — ek popular key expire hui, 10,000 requests ek saath DB pe. Kaise rokoge? (lock, early-refresh, jitter) `[P]`
38. **Cache penetration** — koi aise keys maang raha jo exist hi nahi karti, har baar DB hit. Fix? (null-caching, bloom filter) `[P]`
39. **Cache avalanche** — bahut saari keys ek saath expire → DB spike. Fix? (TTL jitter) `[P]`
40. Redis latency achanak spike — kya-kya dekhoge? (slowlog, big keys, `KEYS *`, swap, fork on save) `[P]`
41. Koi dev ne production pe **`KEYS *`** chala diya — kya galat hua aur sahi kya tha? (`SCAN`) `[P]`
42. Redis memory 100% — writes fail / evictions aggressive. On-call ho, kya karoge? `[P]`
43. ShopFlow: **Redis down ho gaya** — order placement pe kya asar? Kyun kuch nahi tootta? `[SF][P]`
44. Cache aur DB consistency — evict pehle karoge ya DB update pehle? Race condition kahan? `[SF][P]`
45. Kya cache correctness ke liye source-of-truth ho sakta hai? ShopFlow stock ke liye jawab kya aur kyun? `[SF][P]`

---

### Drill log (apni progress yahan tick karo)

| # | Topic | Attempted | Confident |
|---|---|---|---|
| 1–7 | Fundamentals | ☐ | ☐ |
| 8–13 | Data structures | ☐ | ☐ |
| 14–19 | Caching patterns | ☐ | ☐ |
| 20–25 | Eviction & persistence | ☐ | ☐ |
| 26–30 | HA & scaling | ☐ | ☐ |
| 31–36 | Distributed patterns | ☐ | ☐ |
| 37–45 | Prod failures | ☐ | ☐ |

> Tip: `[SF]` wale pehle pakke karo (wahi "my project" proof hai), phir `[P]` failure scenarios — senior round inhi pe tikta hai. Jahan ShopFlow mein feature nahi hai, honestly bol: "concept jaanta hoon, mere project mein X isliye nahi kiya, hota to aise karta."
