# shopflow-resilience — circuit breaker, retry, bulkhead

The patterns that keep one slow/failing dependency from taking your service down with it — the ideas
behind resilience4j, built small and fully unit-tested (pure Java, no infra).

> Plain library like `shopflow-outbox` — never boots.

---

## Circuit breaker
Stop calling a dependency that's clearly down. Count failures over a sliding window; cross the threshold →
trip **OPEN** and fail fast (no threads piling up waiting on a dead service); after a cool-off go
**HALF_OPEN** and let a few trial calls test the water; all succeed → **CLOSED**, any fail → **OPEN** again.
```
CLOSED --(fail rate >= threshold)--> OPEN --(cooloff elapsed)--> HALF_OPEN --(trials pass)--> CLOSED
                                       ^                              |
                                       +-----(a trial fails)----------+
```
Permission-based API: `tryAcquirePermission()` → make the call → `recordSuccess()`/`recordFailure()`.

## Retry with backoff + jitter
Retry only what's **retryable** (transient timeout/5xx — not a 400; retrying a deterministic failure just
burns attempts). Between attempts, back off — and **jitter** the backoff, because the subtle failure is the
*thundering herd*: N clients that failed together, retrying on the same exponential schedule, wake at the
same instants and re-stampede the recovering service. Jitter spreads them out.
| Strategy | Wait at attempt *a* | |
|---|---|---|
| fixed | constant | simple; herd not addressed |
| exponential | base·2^(a-1), capped | backs off fast; still synchronised |
| **full jitter** | random in `[0, exp]` | AWS's recommended default |
| equal jitter | random in `[exp/2, exp]` | keeps a floor, still spreads |

## Bulkhead
Cap concurrent calls to a dependency (a semaphore). If it goes slow, unbounded callers would exhaust the
thread pool and sink the whole service; the bulkhead rejects past N so the rest keeps serving. Named for a
ship's watertight compartments.

## Adaptive concurrency limit (AIMD)
A Bulkhead's `N` is a hand-tuned guess, and the real capacity changes with load and with a slowing
dependency. `AimdConcurrencyLimiter` finds `N` itself using TCP's congestion-control rule — **Additive
Increase / Multiplicative Decrease**: while requests complete under the latency timeout AND the limit is
actually being pressed, nudge it up by 1; the moment a request is slow or dropped, cut it by a backoff
factor (e.g. ×0.9). The limit rides just below where latency starts climbing, and retreats fast on stress.
The asymmetry is the point (same as TCP): probe gently so you don't cause the overload, retreat hard so you
relieve the downstream immediately. `tryAcquire()` sheds when at the limit; `onSuccess(rtt)` / `onDropped()`
feed the controller. (The shape of Netflix's concurrency-limits `AIMDLimit`; it won't inflate while idle.)

## How they stack (the order matters)
A call to a flaky dependency is typically wrapped, outside-in:
```
Retry( CircuitBreaker( Bulkhead( RateLimiter( TimeLimiter( call ) ) ) ) )
```
— the breaker inside the retry means retries also fail-fast once it's open (no pointless waiting);
the bulkhead caps concurrency even across retries. (Rate limiter is `shopflow-ratelimit`; a time limiter is
the one piece left out here since it needs a thread to interrupt.)

## Use it
```java
var cb = new CircuitBreaker(CircuitBreakerConfig.defaults(), System::currentTimeMillis);
if (cb.tryAcquirePermission()) {
    try { var r = inventory.reserve(...); cb.recordSuccess(); }
    catch (Exception e) { cb.recordFailure(); throw e; }
} else {
    // fail fast / fallback
}

var retry = new Retry(3, Backoffs.fullJitter(100, 2000, new Random()),
        e -> e instanceof java.io.IOException, ms -> Thread.sleep(ms));
String out = retry.execute(() -> httpClient.get(url));
```

## Where it fits in shopflow
order-service's `InventoryClient` already uses resilience4j `@Retry` + `@CircuitBreaker` (jittered backoff,
COUNT_BASED window — Walks 39/56). This module is that stack built by hand so each piece is inspectable.

## Tests
`CircuitBreakerTest` (opens on threshold, fails fast, half-open-after-timeout → close on trials, reopen on a
trial failure — all via a fake clock), `RetryTest` (succeeds after transient failures, exhausts + rethrows,
skips non-retryable — backoff schedule asserted with a recording sleeper), `BulkheadTest` (admit N then
reject then release), `BackoffsTest` (fixed/exponential-capped, full/equal jitter within bounds),
`AimdConcurrencyLimiterTest` (sheds at the limit; healthy pressing load grows it; a slow success or a drop
backs it off ×backoff; clamped at min/max; idle healthy traffic does NOT inflate it; bad config rejected).
