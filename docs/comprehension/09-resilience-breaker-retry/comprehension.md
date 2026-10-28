# 09 · When inventory is down: retry, breaker, timeout — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service/…/InventoryClient.java` (`@Retry` + `@CircuitBreaker`), `application.yml` (resilience4j config), `OrderService.placeOrder` (`CallNotPermittedException` → `FAILED`).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's resilience design. Read once, then answer.*

order-service calls inventory-service over HTTP to reserve stock. That call can fail in two very different ways, and the system treats them differently.

**Technical failures** — a timeout, a connection refused, a 5xx — are *transient*: the dependency is briefly unhealthy and a retry might succeed. **Business failures** — a `409 Conflict` (out of stock) or `404` (unknown SKU) — are *definitive*: retrying will give the same answer forever.

Every call to inventory is wrapped, in Micrometer/Resilience4j terms, as **`Retry(CircuitBreaker(call))`**. First, timeouts are bounded: a **1-second connect timeout** and a **2-second read timeout**, so no call waits forever. Then:

- **Retry** (`name: inventory`): up to **3 attempts**, 200ms initial wait with **exponential backoff** (×2). Crucially, it retries **only transient technical exceptions** (`ResourceAccessException`, `HttpServerErrorException`) — a business `InventoryRejectedException` is **not** retried.
- **Circuit breaker** (`name: inventory`): a **count-based** window of the last 10 calls (minimum 5 before it judges); if **≥ 50%** failed, it **opens** and, for 10 seconds, every call **fails fast** with `CallNotPermittedException` instead of trying the dead service. After 10s it goes **half-open**, allows 2 trial calls, and closes again if they succeed. The breaker is configured to **ignore `InventoryRejectedException`** — a customer being told "out of stock" is a *success* of the system, not a failure, so business rejects must never trip the breaker.

When the breaker is open (or retries are exhausted), `placeOrder` catches `CallNotPermittedException`/`RestClientException`, marks the order **`FAILED`**, and returns — the pod's readiness stays **UP**, so order-service keeps serving. The reconciler settles those `FAILED` orders later (see topic 04). Retrying `reserve` is safe because inventory-service treats `orderRef` as an **idempotency key**, so a retried reserve never double-decrements stock.

The three patterns do different jobs: **timeout** bounds a single call, **retry** rides out a blip, **breaker** stops you from hammering a service that's actually down (and gives it room to recover).

---

## Questions

### A — Literal
1. What are the two timeouts and their values, and what do they prevent?
2. Which exception type is explicitly **not** retried and **ignored** by the breaker, and why?
3. Describe the breaker's open → half-open → closed cycle with the configured numbers.

### B — Inference
4. The breaker is wrapped *inside* retry (`Retry(CircuitBreaker(call))`). Once the breaker is open, what happens to a retry attempt, and why is that ordering sensible?
5. Why would it be actively harmful for an out-of-stock `409` to count toward the breaker's failure rate?

### C — Trade-off
6. Retry can make a struggling service worse ("retry storm"). What two settings here limit that, and what's the remaining risk under a broad outage?
7. On breaker-open, order-service marks the order `FAILED` and returns success-ish (readiness stays UP) rather than returning an error to the customer. What does this buy, and what must exist downstream for it to be acceptable?

### D — Transfer / extension
8. A new call to payment-service (synchronous, hypothetically) is added. It is **not** idempotent on retry. Which of the three patterns must you reconsider before copying this config, and what would you change?
9. During a partial inventory outage, ~40% of calls fail — just under the 50% threshold, so the breaker never opens. Describe the user-visible symptom and one config change that would make the system shed load sooner.

### E — Spot-the-flaw
10. A dev "simplifies" the client by removing the timeouts (relying on retry + breaker only). Under a network partition where inventory accepts the connection but never responds, what now happens to order-service threads, and why do retry and breaker **not** save you?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Connect timeout 1s** and **read timeout 2s**. They prevent a single call from waiting forever (a hung dependency would otherwise tie up the calling thread and, under load, exhaust the pool).
2. **`InventoryRejectedException`** (business `409`/`404`). It's not retried because the answer won't change, and the breaker ignores it because "out of stock" is a correct outcome, not a sign inventory-service is unhealthy — counting it would open the breaker during a popular-but-in-stock-limited sale.
3. Count-based window of the last 10 calls, minimum 5 before judging. If ≥50% failed → **open**: for 10s every call fails fast with `CallNotPermittedException`. Then **half-open**: 2 trial calls allowed; if they succeed → **closed** (normal), if they fail → back to **open**.
</details>

<details>
<summary>B — Inference</summary>

4. When the breaker is open, the wrapped call throws `CallNotPermittedException` **immediately** without touching the network; the retry sees that and (since `CallNotPermittedException` isn't in its retry list) **does not keep retrying** a service the breaker has already declared down. The ordering is sensible because the breaker should **short-circuit the retries** — you don't want to retry 3× against a service you already know is open/dead; that would defeat the breaker's purpose of giving it room to recover.
5. Because an out-of-stock `409` means the system **worked correctly** — inventory-service answered, promptly and truthfully. If `409`s counted as failures, a flash sale where most buyers are legitimately rejected would push the failure rate over 50% and **open the breaker**, which would then fail-fast *healthy* calls and mark orders `FAILED` — turning a working "sold out" into a fake outage.
</details>

<details>
<summary>C — Trade-off</summary>

6. **max-attempts 3** (a hard cap, not infinite) and **exponential backoff** (200ms ×2, so retries space out instead of bursting). Remaining risk: under a broad outage, every one of many pods still makes up to 3 attempts before the breaker opens, so there's an initial surge; the breaker opening is what ultimately stops the storm. (Jitter would further de-synchronise retries across pods.)
7. It keeps order-service **available and contained**: the customer's order is accepted and recorded as `FAILED` rather than erroring out, the pod stays in rotation (no cascade), and the order is resolved automatically later. For this to be acceptable, the **reconciler** must exist to settle `FAILED` orders (release idempotently → `CANCELLED`), and there must be **alerting** (breaker-open / `FAILED`-rate) so humans know inventory is down — otherwise "FAILED but UP" silently hides an outage.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Retry** must be reconsidered: retrying a **non-idempotent** call can double-charge. ShopFlow's `reserve` is safe to retry only because inventory-service dedupes on `orderRef`. For a non-idempotent payment call you'd either (a) make it idempotent first (an idempotency key the callee dedupes on), then retry is safe, or (b) **don't retry** (max-attempts 1) and rely on timeout + breaker + a reconcile/verify step. Timeout and breaker are safe to copy; retry is the dangerous one.
9. **Symptom:** ~40% of orders intermittently go `FAILED` while the service looks "up" — a flaky, partial degradation that never trips the breaker, so it persists. **Config change:** lower `failure-rate-threshold` (e.g. to 30%) so the breaker opens sooner and fails fast / sheds load during partial outages, or add a **slow-call-rate** threshold so calls that are slow-but-not-failing also count. (Trade-off: a lower threshold is twitchier and can open on normal noise.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Without the read timeout, a call where inventory **accepts the connection but never responds** blocks the calling thread **indefinitely**. Retry doesn't help — it only triggers *after* a call fails, and this call never returns to fail. The breaker doesn't help either — it only opens based on calls that have *completed* as failures; a call hanging forever never records an outcome, so the failure rate doesn't climb and the breaker stays closed. Under load every thread ends up parked on a dead socket → **thread/connection-pool exhaustion → the whole service hangs.** The timeout is the pattern that actually bounds this; retry and breaker are built *on top of* calls that return. That's why "every call has a timeout" is the first rule.
</details>

---

## Tod ke dekho — Local (docker compose)

Goal: open the breaker by taking inventory down, watch calls fail fast and orders go `FAILED` while readiness stays UP; then recovery.

```bash
make up && TOKEN=$(make -s token)
ORDER() { curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}'; }

# 1) inventory DOWN -> first few orders retry+timeout (slow), then breaker OPENS -> fast FAILED
docker compose -f shopflow/docker-compose.yml stop inventory-service
for i in $(seq 1 8); do ORDER | jq -c '{status, failureReason}'; done
#   first calls slower (retries + 2s read timeout), later calls return FAST (CallNotPermitted) -> status FAILED
curl -s http://localhost:8081/actuator/health/readiness | jq        # STILL UP

# 2) see the breaker metric (open=1)
curl -s http://localhost:8081/actuator/prometheus | grep 'resilience4j_circuitbreaker_state' | grep 'inventory'

# 3) business reject does NOT trip breaker: bring inventory up, order a huge qty -> 409 REJECTED, breaker stays closed
docker compose -f shopflow/docker-compose.yml start inventory-service; sleep 20
curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PS5-SLIM","quantity":100}' | jq '.status'  # REJECTED (not FAILED)
```

**Proved:** technical failure → retry → breaker open → fast `FAILED` (service stays UP); business `409` → `REJECTED`, breaker untouched. That's the technical-vs-business split, live.

> Tune to see it fast: `resilience4j.circuitbreaker.instances.inventory.*` and `.retry.*` in `order-service/application.yml`.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — order+inventory chahiye.

---

## Interview concepts is passage se
- **Timeout / retry / circuit breaker** — teen alag kaam (bound call / ride blip / stop hammering).
- **Transient vs business failure** — kya retry/count karein, kya nahi.
- **`Retry(CircuitBreaker(call))` ordering** — breaker short-circuits retries.
- **Exponential backoff + max-attempts** — retry storm rokna; jitter.
- **Idempotency gate for retry** — non-idempotent call retry mat karo blindly.
- **Fail-fast + mark FAILED + readiness UP** — containment, reconciler + alert ke saath.
- **Slow-call / threshold tuning** — partial outage pe sooner shed.

**Interviewer's lens:** "timeout kyu zaroori jab retry+breaker hain" (hanging call) aur "409 breaker mein kyu nahi" — ye do questions senior se farak karte hain.

> Interview line: *"inventory call `Retry(CircuitBreaker(call))` se wrapped hai, 1s/2s timeouts ke saath. Transient errors (timeout/5xx) 3 baar exp-backoff se retry; business 409/404 na retry na breaker-count (warna flash sale breaker khol de). 10-call window, 50% fail pe breaker open → 10s fast-fail (CallNotPermitted) → order FAILED, par readiness UP, reconciler baad mein settle. reserve retry safe kyunki orderRef idempotency key. Timeout isliye zaroori ki hanging call ko na retry na breaker bachata."*
</content>
