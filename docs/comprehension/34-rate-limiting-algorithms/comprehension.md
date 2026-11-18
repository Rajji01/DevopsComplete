# 34 · Rate limiting: five algorithms, local vs distributed, fail-open — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-ratelimit` module: `RateLimitAlgorithm` (the five), `local/{FixedWindow,SlidingWindowLog,SlidingWindowCounter,TokenBucket,LeakyBucket}Limiter.java`, `redis/RedisRateLimiter.java` (one Lua script per algorithm, atomic), `FailOpenRateLimiter.java` (fail-open vs fail-closed + `ratelimit.backend.errors`). Closes the gap topic [26](../26-scaling-100x-bottlenecks/) named ("per-pod limiter → need a global one") and shares the fail-open idea with [12 caching](../12-caching-cache-aside-failopen/).
> **Theme:** rate limit "50/s" kehna aasaan; **kaunsा algorithm**, **local ya distributed**, aur **limiter khud mar jaaye to kya** — yahi asli design hai.

---

## Scenario (read cold)

> *A review of `shopflow-ratelimit`: "we support five algorithms and two backends — explain the trade-offs and when each wins." Read once, then answer.*

ShopFlow's order API already has a per-pod limiter (topic 05/26: `@RateLimiter`, 50/s **per pod**), which protects one pod but drifts with replica count — not a global contract. The `shopflow-ratelimit` module is the toolkit for doing it properly: **five algorithms**, selectable by config, each available as a **local** (in-memory) or **distributed** (Redis + Lua) backend.

**The five algorithms, each a different shape:**
- **Fixed window** — one counter per clock-aligned window (each calendar minute), reset to 0 on rollover. Trivial, **O(1) memory**. Flaw: the **boundary burst** — `limit` requests in the last instant of window N plus `limit` in the first instant of N+1 lets **2× through** in a sub-window span.
- **Sliding window log** — store a **timestamp per request**; allow iff fewer than `limit` timestamps fall in the last `window`. **Exact, no boundary burst** — but **O(limit) memory per key** (the expensive one).
- **Sliding window counter** — keep two numbers: this window's count and the previous window's, and estimate the rolling count by **weighting the previous window by how much of it still overlaps**: `estimate = prevCount * overlapFraction + currCount`. **O(1) memory**, smooths the boundary, **slightly approximate** (assumes previous requests were spread evenly). The usual default (Cloudflare popularised it).
- **Token bucket** — a bucket holds up to `capacity` tokens, refills at `refillPerSecond`; each request spends one. Allows a **controlled burst** (an idle client can spend the whole bucket, then is held to the refill rate). Refilled **lazily** (add `elapsed × rate` on each call, capped), so an idle key costs nothing. Most API gateways (AWS, Stripe, GitHub) use it.
- **Leaky bucket** — a bucket with a hole that **drains at a fixed rate**; each request pours in one unit, reject on overflow. Enforces a **smooth, near-constant output rate** (no bursts) — for protecting a **fragile downstream that hates spikes**.

**Two backends.** **Local** (in-memory, `ConcurrentHashMap.compute`/`synchronized` for atomicity) is per-pod — fast, no network, but each pod limits independently (the topic-26 drift). **Distributed** (`RedisRateLimiter`) puts the decision **in Redis**, so **every pod shares one source of truth and one clock**; each algorithm is a **Lua script** that runs **atomically** — because doing GET-then-INCR from Java would race (two pods both read "under limit" and both allow). One Java class serves all five; only the script differs. Every script returns the same shape `[allowed, remaining, retryAfterMs]`, and Redis `PEXPIRE` reaps idle keys for free.

**And the prod question everyone forgets:** what if the **limiter itself** is down (Redis unreachable, timeout, bad reply)? You must **decide on purpose**, because the default — an uncaught exception — fails the whole request. `FailOpenRateLimiter` wraps the backend: **fail-open** (on error, **allow**) trades a brief unthrottled window for availability (right for most APIs — a limiter outage shouldn't take the API down); **fail-closed** (on error, **deny/429**) trades availability for protection (right when overload is catastrophic — a payment / costly downstream). Either way it emits **`ratelimit.backend.errors`** so a silent limiter outage becomes a **visible alert**, not a mystery.

---

## Questions

### A — Literal
1. Describe the fixed-window "boundary burst" precisely and why it lets 2× through.
2. Token bucket vs leaky bucket: what shape does each enforce, and which protects a spike-hating downstream?
3. Why must the distributed backend's per-algorithm logic be a **Lua script** rather than GET-then-INCR from Java?

### B — Inference
4. Sliding window **log** is exact; sliding window **counter** is approximate but the usual default. What exactly makes the counter "approximate," and why is it preferred anyway?
5. Token bucket refills **lazily** (`elapsed × rate` on each call) instead of a background timer. What two problems does lazy refill avoid?

### C — Trade-off
6. Local (in-memory) vs distributed (Redis) backend: what does each cost and buy, and how does the distributed one fix the exact problem topic 26 named about the per-pod `@RateLimiter`?
7. Fail-open vs fail-closed when the limiter is down. Give a ShopFlow endpoint for each and the risk you accept.

### D — Transfer / extension
8. You must enforce "100 orders/sec **per customer**, globally across all pods," and bursts of up to 20 are fine. Which algorithm, which backend, and what key — and why not the per-pod `@RateLimiter`?
9. A downstream payment processor allows **exactly 500 req/s total** and melts above it. Which algorithm best matches that constraint, which fail mode, and why?

### E — Spot-the-flaw
10. A dev deploys the **in-memory token bucket** on a service autoscaled to 10 pods to enforce "1000 req/s per API key," setting each pod's capacity to 1000. Under load, clients get far more than 1000/s and it varies with traffic. Explain the bug and the fix.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. The counter resets at each window boundary. A client sends `limit` requests in the **last instant** of window N (all allowed, counter hits limit, then resets) and another `limit` in the **first instant** of window N+1 (fresh counter, all allowed). Those `2 × limit` requests land within a span **shorter than one window**, so the *effective* rate over that sliding span is **2×** the configured limit — the window's fixed boundaries don't "see" the burst straddling them.
2. **Token bucket** enforces a **burst-up-to-capacity-then-steady** shape (you can save up tokens and spend them at once). **Leaky bucket** enforces a **smooth, near-constant output rate** (it only drains at the leak rate, so you can't push faster for long). **Leaky bucket** protects a spike-hating downstream — the smooth trickle is the whole point.
3. Because the check-and-update must be **atomic**. GET-then-INCR from Java has a gap between reading the count and writing the increment; two pods (sharing Redis) can both GET "under limit," both decide allow, both INCR — **over-admitting**. A **Lua script runs atomically inside Redis** (single-threaded execution of the whole script), so the read and the write can't interleave across clients — the same reason the distributed lock used Lua (topic 32).
</details>

<details>
<summary>B — Inference</summary>

4. The counter keeps only **two numbers** (current + previous window counts) and **estimates** the rolling count by assuming the previous window's requests were **spread evenly**, weighting it by the overlap fraction. If the previous window's traffic was actually **bursty** (all at its end or start), the even-spread assumption is wrong, so the estimate can be **off by a little**. It's preferred anyway because it's **O(1) memory** (vs the log's O(limit)), **smooths the boundary burst** (unlike fixed window), and the small inaccuracy is an accepted trade for huge memory savings — good enough for almost all APIs.
5. Lazy refill avoids: **(a)** a **background timer/thread per key** (which would be enormous overhead for millions of keys and wouldn't scale), and **(b)** doing **work for idle keys** — a key touched once an hour costs nothing in between; you compute the refill only when a request actually arrives (`add elapsed × rate, capped at capacity`). It's both exact and cheap.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Local (in-memory):** fastest (no network hop), simplest, but **per-pod** — each pod counts independently, so the global limit is `perPod × replicas` and **drifts as the HPA scales** (exactly topic 26's complaint about `@RateLimiter 50/s/pod`). **Distributed (Redis):** one **shared counter and clock** across all pods → a true **global** limit, at the cost of a Redis round-trip per request and a dependency on Redis. The distributed backend fixes topic 26's drift: the cap becomes a real number (e.g. "1000/s total") regardless of replica count, because every pod decrements the **same** Redis state.
7. **Fail-open:** the **order/browse** API — if Redis (the limiter) is down, allow requests so the shopping flow stays up; risk = a brief unthrottled window (acceptable, the DB/other limits still exist). **Fail-closed:** a **payment or costly third-party** endpoint — if the limiter is down you'd rather **429** than flood a downstream that melts/costs money above its cap; risk = you reject legitimate traffic during a limiter outage. Either way `ratelimit.backend.errors` alerts so the outage is visible.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Token bucket** (bursts of 20 are fine → `capacity = 20`, `refillPerSecond = 100`), **distributed/Redis** backend (the limit is **global across pods**), keyed by **customer id** (`ratelimit:orders:{customerId}`). Not the per-pod `@RateLimiter` because that would give `100 × replicas` per customer and drift with autoscaling — it can't express a **global per-customer** contract; only a shared (Redis) counter per-customer-key can.
9. **Leaky bucket**, because the processor needs a **smooth ≤500/s** with **no bursts** — leaky bucket enforces a constant output rate (token bucket would let a saved-up burst exceed 500/s momentarily and melt it). **Distributed** backend keyed by the **processor** (a single global bucket, not per-customer), and **fail-closed** (if the limiter is down, deny rather than risk flooding a downstream that melts above 500/s — protection over availability). 
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The **in-memory** token bucket is **per-pod**: with 10 pods each configured to `capacity/refill = 1000/s`, the **cluster** allows up to **10 × 1000 = 10,000/s** per key, not 1000 — and it **varies with the pod count** (autoscaling changes the multiplier). Each pod honestly enforces 1000/s against **its own** bucket, oblivious to the others. **Fix:** use the **distributed (Redis) backend** so all pods share **one** bucket per key — then "1000/s per key" holds globally regardless of replicas. (Same class of bug as topic 26's per-pod limiter; the fix is a shared source of truth, exactly what the Redis backend provides.)
</details>

---

## Tod ke dekho — Local (real module + the demo app)

```bash
# the five algorithms and their trade-off comments:
sed -n '1,25p' shopflow/shopflow-ratelimit/src/main/java/com/shopflow/ratelimit/RateLimitAlgorithm.java
ls shopflow/shopflow-ratelimit/src/main/java/com/shopflow/ratelimit/local/     # the 5 local impls
ls shopflow/shopflow-ratelimit/src/main/resources/ratelimit/ 2>/dev/null       # the 5 Lua scripts (distributed)
grep -n "failOpen\|ratelimit.backend.errors" shopflow/shopflow-ratelimit/src/main/java/com/shopflow/ratelimit/FailOpenRateLimiter.java

# run the tests (each algorithm's shape + boundary burst + atomicity):
cd shopflow && ./mvnw -q -pl shopflow-ratelimit test

# there's a demo app/controller — hit it live:
grep -n "class RateLimitDemo" shopflow/shopflow-ratelimit/src/main/java/com/shopflow/ratelimit/web/RateLimitDemoController.java
```

**Proved:** five algorithms (fixed/log/counter/token/leaky), two backends (in-memory per-pod vs Redis-Lua global), and the fail-open/closed wrapper with its error metric — the full toolkit the per-pod `@RateLimiter` was a first step toward.

## Tod ke dekho — Online
Redis chahiye (distributed backend) → [`ONLINE-LABS.md`](../ONLINE-LABS.md): Upstash Redis, run the demo with `ratelimit.backend=redis`, hammer one key from two "pods" (two processes) and watch a **shared** limit hold — then kill Redis and watch fail-open vs fail-closed + the `ratelimit.backend.errors` metric.

---

## Interview concepts is passage se
- **5 algorithms** — fixed (boundary burst), log (exact/O(limit)), counter (O(1)/approx/default), token (burst, gateways), leaky (smooth, fragile-downstream).
- **Local vs distributed** — per-pod drift (topic 26) vs Redis shared counter+clock; **Lua = atomic** (no GET-INCR race).
- **Lazy refill** — no timer, no work for idle keys.
- **Fail-open vs fail-closed** — limiter outage decision; availability vs protection; `ratelimit.backend.errors` alert.
- **Key choice** — per-customer vs per-downstream; global contract needs a shared key.
- **Retry-After** — `[allowed, remaining, retryAfterMs]` shape.

**Interviewer's lens:** kya tu algorithm ko use-case se match kar sakta (burst→token, smooth→leaky, default→sliding-counter), **per-pod vs distributed** ka farak (topic 26 drift fix = Redis), aur **limiter-down** ka fail-open/closed decision bol sakta.

> Interview line: *"Rate limit sirf '50/s' nahi — **algorithm**, **backend**, aur **limiter-down** teen decisions. Paanch: fixed-window (boundary pe 2x burst), sliding-log (exact par O(limit) memory), sliding-counter (O(1), approx, default — Cloudflare), token-bucket (burst allow, gateways/Stripe), leaky-bucket (smooth rate, fragile downstream). **Local in-memory = per-pod** (topic 26 wala drift, cap = perPod×replicas), **Redis distributed = global** (shared counter+clock, Lua atomic warna GET-INCR race). Limiter khud down ho to **fail-open** (allow, availability — zyadatar APIs) ya **fail-closed** (429, protection — payment/costly downstream), aur `ratelimit.backend.errors` se alert. Global per-customer limit → token-bucket on Redis keyed by customer, per-pod `@RateLimiter` se nahi."*
