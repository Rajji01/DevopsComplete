# 34 · Rate limiting — NOTES

> Pair: [`comprehension.md`](comprehension.md). Real `shopflow-ratelimit` (5 algos × 2 backends + fail-open). Topic 26 ka "global limiter" gap yahan band hota.

---

## KYA (one breath)
Rate limit = 3 decisions: **kaunsा algorithm** (5), **local ya distributed** (per-pod drift vs Redis global), **limiter down to kya** (fail-open/closed). "50/s" kehna aasaan, ye design hai.

## EASY WAY
> **"Burst chahiye → token bucket. Smooth chahiye → leaky. Default → sliding-counter. Global (sab pods) → Redis+Lua, per-pod nahi. Redis down → soch ke decide (allow ya 429)."**

## 5 ALGORITHMS
| Algo | Memory | Boundary burst? | Shape | Kab |
|------|--------|-----------------|-------|-----|
| **Fixed window** | O(1) | **haan (2x)** | counter/window | sabse sasta, 2x overshoot OK |
| **Sliding log** | **O(limit)** | nahi (exact) | har request ka timestamp | exactness zaroori |
| **Sliding counter** | O(1) | nahi (smooth) | prev×overlap + curr (approx) | **default** (Cloudflare) |
| **Token bucket** | O(1) | — | **burst** up to capacity, lazy refill | APIs/gateways (AWS/Stripe/GitHub) |
| **Leaky bucket** | O(1) | — | **smooth** constant drain | fragile downstream (no spikes) |

## TOKEN vs LEAKY (sabse poocha)
```
 TOKEN BUCKET: idle → tokens save → ek saath burst (up to capacity) → phir steady refill
   "save up then spend" — API ke liye accha (idle client ko burst allow)
 LEAKY BUCKET: bucket with hole, fixed drain; overflow → reject
   smooth output, burst nahi — fragile downstream (payment processor 500/s) protect
```

## LOCAL vs DISTRIBUTED (topic 26 fix)
```
 LOCAL (in-memory, ConcurrentHashMap.compute):  per-pod → cap = perPod × replicas → DRIFT (topic 26)
 DISTRIBUTED (Redis + Lua):  ek shared counter + ek clock → true GLOBAL limit
   har algo = ek Lua script, ATOMIC (GET-then-INCR Java se karo → 2 pods dono "under limit" → over-admit)
   return shape: [allowed, remaining, retryAfterMs]; idle keys PEXPIRE se reap
```

## FAIL-OPEN vs FAIL-CLOSED (limiter khud down)
| Mode | Error pe | Trade | Kab | ShopFlow |
|------|----------|-------|-----|----------|
| **fail-open** | **allow** | availability > protection | zyadatar APIs (brief unthrottled OK) | order/browse |
| **fail-closed** | **deny 429** | protection > availability | overload catastrophic | payment/costly downstream |
- Dono `ratelimit.backend.errors` emit karte → silent limiter outage **visible alert** ban jaata (not mystery). (Topic 12 fail-open cache ka cousin.)

## LAZY REFILL (token bucket)
- Background timer **nahi** (millions keys pe overhead + idle keys pe kaam). Har call pe `elapsed × rate` add (cap at capacity). Idle key = free.

## DECISION (kaunsа kab)
| Chahiye | Algo + backend + key |
|---------|----------------------|
| per-customer global, burst OK | **token bucket** + Redis, key=customerId |
| downstream exactly 500/s, no spike | **leaky bucket** + Redis, key=downstream, **fail-closed** |
| cheap + 2x overshoot OK | fixed window (local) |
| exact, no burst, memory OK | sliding log |
| general default | sliding counter |

## UNDERSTANDING CHECK (khud bol)
1. Fixed-window 2x burst kaise? → *window N ke last + N+1 ke first, boundary straddle, counter reset.*
2. Sliding-counter approx kyun, phir bhi default kyun? → *prev-window even-spread assume; O(1) memory + boundary smooth = worth it.*
3. Distributed Lua kyun? → *atomic; GET-INCR race = 2 pods over-admit.*
4. In-memory token bucket 10 pods pe 1000/s = asli kitna? → *10×1000=10000/s (per-pod); fix = Redis shared bucket.*
5. Payment-processor 500/s cap, limiter down → fail kya? → *fail-closed (deny) — flood se melt na ho; leaky bucket.*

## INTERVIEW LINE
> *"Rate limit = algorithm + backend + limiter-down, teeno. 5 algo: fixed (2x boundary burst), sliding-log (exact/O(limit)), sliding-counter (O(1)/approx/default), token-bucket (burst, gateways), leaky-bucket (smooth, fragile downstream). **Local = per-pod** (topic 26 drift, cap=perPod×replicas), **Redis = global** (shared counter+clock, Lua atomic — GET-INCR race rok). Limiter down: **fail-open** (allow, availability) ya **fail-closed** (429, protection), `ratelimit.backend.errors` alert. Global per-customer → token-bucket Redis keyed by customer, `@RateLimiter` per-pod se nahi."*

---
**Grounded:** `shopflow-ratelimit/RateLimitAlgorithm.java` (5 enum + trade-offs), `local/*Limiter.java` (fixed/log/counter/token/leaky), `redis/RedisRateLimiter.java` (Lua per-algo, atomic, `[allowed,remaining,retryAfterMs]`, PEXPIRE), `FailOpenRateLimiter.java` (fail-open/closed + `ratelimit.backend.errors`). Ties: topic [26](../26-scaling-100x-bottlenecks/) (per-pod→global), [12](../12-caching-cache-aside-failopen/) (fail-open), [32](../32-distributed-locks-fencing/) (Lua atomic).
