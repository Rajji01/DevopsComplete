# shopflow-ratelimit — six algorithms, two backends, one config knob

Rate limiting = "how many operations may this key do in this much time?" Every algorithm below answers
that; they differ in **how they shape bursts** and **what they cost**. This module implements all five,
each with an **in-memory** backend (single node, used for tests/dev) and a **Redis + Lua** backend
(distributed, atomic, prod). Which one is live is pure config.

---

## The algorithms (what, trade-off, when)

| Algorithm | Idea | Memory/key | Burst behaviour | Use when |
|---|---|---|---|---|
| **Fixed window** | one counter per clock-aligned window, reset on roll | O(1) | allows a **2× boundary burst** (end of window N + start of N+1) | cheapest; a brief 2× overshoot is OK |
| **Sliding window log** | store a timestamp per request; count those in the last window | **O(limit)** | exact, no burst | you need exactness and keys aren't huge |
| **Sliding window counter** | weight the previous window by how much it still overlaps | O(1) | smooth, slightly approximate | **the usual default** (Cloudflare) |
| **Token bucket** | refill tokens at a rate up to a capacity; spend to act | O(1) | **allows bursts** up to capacity, then steady | APIs that should tolerate bursts (AWS/Stripe/GitHub) |
| **Leaky bucket** | a bucket that drains at a fixed rate; overflow → reject | O(1) | **no bursts** — smooth output | protecting a fragile downstream from spikes |
| **GCRA** | one timestamp (Theoretical Arrival Time); allow iff the projected arrival ≤ a bucket ahead | **O(1), a single `long`** | same as token bucket (burst to capacity, then paced) | a single-key distributed limiter; exact `Retry-After` for free (redis-cell) |

> Token vs leaky is the one people mix up: **token bucket lets you save up and burst; leaky bucket forces
> a steady trickle.** Same steady rate, opposite burst shape.

The boundary-burst flaw of fixed window, in one picture:
```
limit = 5 per minute
   12:00:59  ######  (5 requests, still "minute 12:00")
   12:01:00  ######  (5 requests, now "minute 12:01")
   -> 10 requests in a 1-second span, both windows "legal"
```
Sliding window (log or counter) is what fixes it.

---

## Backends — why Lua for the distributed one

The in-memory limiters use `ConcurrentHashMap.compute`, which is atomic per key. Across **many pods**
that doesn't help — each pod has its own map, so N pods = N× the limit. The distributed backend keeps the
state in Redis, shared by all pods.

But a naive distributed limiter — `GET count` then `INCR` from Java — has a **race**: two pods both read
"4 < 5", both increment, both allow, and now it's 6. The fix is to make read-decide-write **one atomic
step**. Redis runs a whole **Lua script** with nothing interleaved, so the script *is* the atomic step.
Each algorithm is one `.lua` in [`src/main/resources/ratelimit/`](src/main/resources/ratelimit), heavily
commented. Two prod details baked in:
- **Server clock** (`redis TIME`) inside the script, so all pods share one clock — no client-clock-skew.
- **`PEXPIRE`** on every key, so idle keys evict themselves (no reaper, no unbounded memory).

---

## Run it and test live

Needs JDK 21 (same as the rest of shopflow). From `shopflow/`:
```sh
./mvnw -pl shopflow-ratelimit spring-boot:run
```
Then hammer the endpoint (limit defaults to a small number so you see 429s fast):
```sh
# 20 requests as client "alice" — watch 200s turn into 429s once the allowance is spent
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " -H 'X-Client-Id: alice' localhost:8086/api/ping; done; echo

# headers tell the client how to behave
curl -i -H 'X-Client-Id: alice' localhost:8086/api/ping    # X-RateLimit-Remaining, and on 429: Retry-After

# a different key has its own budget
curl -s -H 'X-Client-Id: bob' localhost:8086/api/ping

# what's live right now
curl -s localhost:8086/api/config
```
To **watch token bucket refill**: spend the bucket, wait a second, try again — one request gets through
per second. To **see the fixed-window boundary burst**: set `algorithm: FIXED_WINDOW`, send `limit`
requests just before a window boundary and `limit` just after.

---

## Switch the active algorithm / backend (configurable)

Edit [`application.yml`](src/main/resources/application.yml) and restart, or override per-run:
```sh
RL_ALGO=SLIDING_WINDOW_COUNTER RL_BACKEND=LOCAL ./mvnw -pl shopflow-ratelimit spring-boot:run
RL_ALGO=TOKEN_BUCKET          RL_BACKEND=REDIS ./mvnw -pl shopflow-ratelimit spring-boot:run   # needs Redis up
```
Keys:
```
ratelimit.algorithm   FIXED_WINDOW | SLIDING_WINDOW_LOG | SLIDING_WINDOW_COUNTER | TOKEN_BUCKET | LEAKY_BUCKET | GCRA
ratelimit.backend     LOCAL | REDIS
ratelimit.limit / .window              (window algorithms: "limit per window")
ratelimit.capacity / .refill-per-second (bucket algorithms)
ratelimit.fail-open   true=allow on backend error, false=deny
```
For `backend=REDIS`, point at the stack's Redis: `REDIS_HOST=localhost` (or `redis` inside compose).

---

## Production scenarios handled (and why)

- **Atomicity / races** — the distributed decision is one Lua script, never GET-then-INCR. (In-memory uses
  `compute`.) This is the difference between a correct limiter and one that leaks under concurrency.
- **Clock skew** — the Lua clock is Redis's `TIME`, not each pod's wall clock.
- **Backend outage** — [`FailOpenRateLimiter`](src/main/java/com/shopflow/ratelimit/FailOpenRateLimiter.java)
  decides on purpose: **fail-open** (allow — availability, the usual choice) or **fail-closed** (deny —
  for an endpoint whose overload is catastrophic), and emits `ratelimit.backend.errors` so the outage is
  visible, not silent.
- **Memory** — every Redis key has a `PEXPIRE`; the in-memory log notes its O(limit) cost (the one to
  avoid for hot keys with large limits).
- **Client cooperation** — every response carries `X-RateLimit-Limit/Remaining`; a 429 carries
  `Retry-After` (whole seconds, rounded **up** so a client never retries too early).
- **Right key** — limit on the **actor** (user id / API key), falling back to IP; limiting on IP alone
  punishes everyone behind a NAT and is trivially bypassed with more IPs.

## Where this fits in shopflow

order-service already rate-limits with resilience4j — but that is **in-memory, per-pod** (10 pods = 10×
the limit). This module is the **distributed** answer the notes flagged as "the next step": one shared
limit across all pods via Redis. It's a standalone, tested library + demo here; wiring it into a service
(a `OncePerRequestFilter` calling `RateLimiter`) is the follow-up.

## Tests

`RateLimiterLocalTest` drives all six in-memory algorithms with a fake clock (deterministic, no infra) —
it both verifies the logic and documents each algorithm's defining behaviour (GCRA: bursts to capacity then
paces at the rate, same as the token bucket, with an exact `Retry-After`). The Lua backend mirrors that
logic (one `.lua` per algorithm, including `gcra.lua`) and is meant to be exercised live against a real Redis.
