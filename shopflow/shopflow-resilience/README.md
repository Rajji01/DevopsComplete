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

## CoDel (controlled delay) request shedding
AIMD and the bulkhead control *how many* requests run at once; CoDel controls *which queued requests to give
up on* when overloaded (Nichols & Jacobson, RFC 8289 — adapted here from packets to requests). The naive
signal is queue **length** ("shed when the queue is too long"), but length lies: a deep queue draining fast
is fine, a short one barely moving is a disaster. What hurts the user is **sojourn time** — how long their
request actually waited — so CoDel measures that. The refinement that makes it work is distinguishing a
*good* queue (a burst that drains within a round-trip) from a *standing* one (persistently backed up):
```
good queue:  sojourn spikes above target, falls back within interval  -> absorb, shed nothing
standing:    sojourn stays >= target for a whole interval             -> start shedding
```
Only once sojourn has stayed at/above `target` (default 5ms) for a full `interval` (default 100ms) does it
start shedding, and then it drops on a schedule that tightens as the backlog persists:
```
dropNext = t + interval / sqrt(count)      count = drops so far this episode
```
so pressure accelerates while the queue stays bad and releases the instant sojourn recovers. `offer(req, now)`
to enqueue; `poll(now)` returns the request to serve plus the list CoDel shed (fail those fast with a 503 —
far better than letting them wait behind a standing queue and time out anyway). This is the AQM behind
`fq_codel` and the shape of Envoy/Facebook adaptive-LIFO overload shedding.

## Phi (φ) accrual failure detector
A circuit breaker watches *your own calls* to decide a dependency is sick. The gossip-world question is the
mirror of that: "is that **peer** still alive?", answered from its **heartbeats** — the problem Cassandra,
Akka/Pekko and ScyllaDB solve to maintain cluster membership. A fixed "no beat for T ms ⇒ dead" timeout is
the trap: too small and a GC pause convicts a healthy node (costly re-replication/churn), too large and a
real crash lingers — and no single T fits both a quiet LAN and a jittery WAN. `PhiAccrualFailureDetector`
replaces the yes/no with a **continuous suspicion level φ** that scales to the peer's *own* heartbeat
regularity:
```
φ(now) = -log10( 1 - F(now - lastHeartbeat) )     F = normal CDF(mean, stddev) of recent inter-arrivals
         φ≈1 → ~10% chance we're wrong to suspect,  φ≈2 → ~1%,  φ≈3 → ~0.1%
```
A metronome-regular peer (tight σ) becomes suspicious almost the instant it's late; a jittery peer (loose σ)
is given slack. The app thresholds φ (convict at, say, 8), cleanly splitting *how we measure doubt* from
*how much we tolerate* — low threshold = faster crash detection but more false positives. This build mirrors
Akka: a cheap logistic approximation of the Gaussian tail (no erf), a `minStdDeviation` floor (so a very
regular peer isn't convicted off one slightly-late beat), an `acceptableHeartbeatPause` added to the mean
(expected GC/scheduler pauses don't count against it), and a seeded first estimate. Time is passed in, so
tests are deterministic. `heartbeat(now)` / `phi(now)` / `isAvailable(now, threshold)`.

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
backs it off ×backoff; clamped at min/max; idle healthy traffic does NOT inflate it; bad config rejected),
`PhiAccrualFailureDetectorTest` (φ=0 before any heartbeat; steady 1s heartbeats keep φ<1 on time and drive
φ>8 when 2s overdue; a jittery history gets more slack than a regular one at the same lateness;
`acceptableHeartbeatPause` suppresses suspicion for an expected gap; bad config rejected),
`CodelQueueTest` (a burst draining within the interval sheds nothing; a standing queue sheds only after
sojourn stays above target for a full interval, then again on the control-law schedule; recovers after the
backlog drains and does not shed a healthy request; empty poll returns nothing; bad config rejected).
