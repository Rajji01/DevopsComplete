# D10 · Redis went down and now the database is on fire

> Skill: caching / capacity debugging. Related mechanism: [`../comprehension/12-caching-cache-aside-failopen/`](../comprehension/12-caching-cache-aside-failopen/), [`../comprehension/07-k8s-readiness-cascade/`](../comprehension/07-k8s-readiness-cascade/).

---

## Page (symptom)

Redis (the inventory-service cache) crashed. Nothing returned an error — no 5xx spike, inventory-service stayed **Ready** and in rotation. But within a minute: inventory-service p99 latency went from ~15ms to ~900ms, Postgres CPU hit 95%, its connection pool is pinned, and `GET /products/{sku}` is crawling. Some order placements are now timing out at the inventory reserve step.

**Before details: nothing is erroring, the service is "Ready", yet everything is slow and the DB is melting. What just happened, and why is "no errors" itself a clue?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- Redis pod: `CrashLoopBackOff`. inventory-service logs: repeated `WARN … cache 'products' GET failed … falling back` (not errors — warnings).
- inventory-service readiness: **UP** (Redis is excluded from the readiness group). Error rate normal; **latency** way up.
- Postgres: CPU ~95%, HikariCP active connections 5/5 per pod (`DB_POOL_SIZE=5`) with a growing wait queue, lots of identical `SELECT … FROM product WHERE sku = ?` queries.
- Traffic is **unchanged** from an hour ago — same request rate, same SKUs (a few hot ones dominate: the sale items).
- When Redis was up, cache hit rate was ~98%, so Postgres saw ~2% of product reads.
</details>

---

## Your move

1. Redis is down but nothing errors and the pod stays Ready. Which design choice produced that behaviour, and why is it *correct* even though the DB is now melting?
2. Traffic didn't change, yet Postgres load exploded. Walk the arithmetic: what happened to the read volume hitting the DB?
3. A few hot SKUs dominate. What specific pile-up does that cause against Postgres the instant the cache disappears?
4. "No errors, high latency, DB saturated" — which mental model (RED vs USE) localizes this, and to what resource?
5. Two mitigations beyond "restart Redis": one that protects the DB during a cache outage, one that stops a single key's miss from stampeding. What does each cost?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Why no errors + still Ready (Q1).** inventory-service's cache is **fail-open**: `CacheConfig` uses a `LoggingCacheErrorHandler`, so a Redis failure is **logged and the read falls through to Postgres** instead of erroring — and Redis is **excluded from the readiness group**, so a cache outage doesn't pull the pods from the load balancer. This is the *correct* design: a cache must never be a hard dependency of a read path (otherwise a Redis blip becomes a full outage, and readiness would cascade — see topic 07). The price of that correctness is exactly what you're seeing: when the cache vanishes, **load shifts to the DB** instead of failing. Fail-open protects *correctness and availability*, not *capacity*.

**The arithmetic (Q2, root cause).** With a ~98% hit rate, Postgres was serving ~2% of product reads. Redis down ⇒ hit rate 0% ⇒ Postgres now serves **100%** — roughly a **50× jump** in read queries, at unchanged user traffic. The DB's connection pool (just 5 per pod, `DB_POOL_SIZE=5`) saturates, queries queue, latency balloons, CPU pegs. The root cause isn't the DB — it's that **the cache outage removed the 98% shield** and the DB was never sized for the unshielded load. This is a **cache stampede / thundering herd**.

**Hot keys make it worse (Q3).** A few hot SKUs (the sale items) got almost all the traffic. When the cache was up, each was one Redis key served in microseconds. Cache gone, **every** request for a hot SKU now hits Postgres for the *same* row simultaneously — many identical `SELECT … WHERE sku = ?` in flight at once, all contending for connections. Concentrated keys turn a 50× average into a much sharper spike on the few rows everyone wants.

**RED vs USE (Q4).** RED on inventory-service shows **normal errors, high duration** — "slow, not broken", so it's saturation or a slow dependency, not a crash. Switch to **USE** on the **Postgres connection pool**: **Utilization** 5/5 per pod, **Saturation** a growing wait queue, **Errors** none. That localizes it precisely: the bottleneck is the **DB connection pool**, starved because the cache stopped absorbing reads. (The missing Redis is the *trigger*; the saturated pool is the *mechanism*.)

**Fixes (Q5):**
- **Restore Redis** — the real fix; hit rate recovers, DB load drops back to ~2%. But bring it back carefully: a cold cache means the first wave still hits Postgres, so you may need to warm it or ramp traffic.
- **Protect the DB during a cache outage:** put a **bulkhead / connection cap or a short circuit breaker on the DB read path** (or a per-endpoint rate limit) so a cache-miss storm can't take Postgres to 95% CPU — the service sheds some read load (a few 429/slow reads) instead of melting the shared DB. **Cost:** some reads are degraded during the outage — a deliberate trade to keep the DB (and everything else using it) alive.
- **Stop a single key stampeding:** **request coalescing / single-flight** (one DB load per key while others wait for its result) or a short **per-key lock**, so 500 simultaneous misses for the same hot SKU become **one** DB query, not 500. **Cost:** a little added latency/complexity on the miss path, and care not to reintroduce a lock that itself becomes a bottleneck.
</details>

---

## The pattern

- **"No errors, service Ready, but slow and the DB is hot" = fail-open cache outage.** The absence of errors is the clue: a correctly fail-open cache converts a Redis outage into DB *load*, not failures.
- **Fail-open protects correctness, not capacity.** The same design that stops a cascade (topic 07) lets a cache outage stampede the DB. Both are true; plan for the capacity side.
- **Hit rate is leverage; losing it multiplies DB load by `1 / (1 − hitRate)`.** 98% → 50×. Know your hit rate, because that's your blast radius when the cache dies.
- **Hot keys concentrate the stampede.** A cache stampede hurts most on the few keys everyone wants; mitigate with single-flight/coalescing, not just "more DB".
- **RED to triage, USE to localize.** Slow-not-broken → saturation → find the full resource (here, the DB pool).

> Grounded in: `inventory-service/config/CacheConfig` (`LoggingCacheErrorHandler` fail-open, Redis excluded from readiness), `ProductController` `@Cacheable("products")` (60s TTL), inventory HikariCP pool. Shared rate-limit / gateway limiter is a STATE §4 open item.
</content>
