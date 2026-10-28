# 04 · Orders stuck in limbo — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `OrderService` (`placeOrder`, `reconcileFailedOrders`, `countStuckConfirmed`, `saveWithEvent`), `OrderReconciler`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's reliability notes. Read once, then answer.*

`placeOrder` is deliberately **not** one big `@Transactional` method. Holding a database transaction (and its pooled connection) open while waiting on a remote HTTP call to inventory-service would tie up a connection for the whole network round-trip; under load the pool drains and the service stalls. So each step is saved on its own, and the **order status records how far the saga got**: `PENDING` (row created) → reserve stock over HTTP → `CONFIRMED` or `REJECTED`. If inventory is unreachable (timeout, or the circuit breaker is open), the order is saved as `FAILED` with a readiness that stays UP.

This separation creates **limbo** states — an order that is stuck part-way:

- **`FAILED`** — we called inventory but never learned the outcome (timeout / breaker open). The stock *might* have been reserved, or not; we genuinely don't know.
- **`PENDING` for a long time** — the pod crashed between "save PENDING" and "save the outcome." Same "outcome unknown" shape.

A scheduled `OrderReconciler` runs every 60 seconds. For `FAILED` **or** `PENDING` orders older than a grace period (default 2 minutes), it calls `inventoryClient.release(orderRef)` and moves them to `CANCELLED`. The key fact that makes this safe: **`release` is idempotent — releasing an order that reserved nothing is a no-op.** So calling it is always safe, and the customer gets a definite `CANCELLED` instead of being stuck forever. If inventory is still down, the batch **stops** at the first failure and retries next minute rather than hammering a dead dependency.

But there is a state the reconciler **deliberately does not touch**: `CONFIRMED` (and `REFUND_PENDING`) orders that are stuck. These are a *different* kind of limbo. A `CONFIRMED` order is waiting for payment-service to reply over Kafka — and payment-service might just be slow (its pod restarting, consumer lag), and **about to** reply with a success. Auto-cancelling it could cancel an order that was in fact **paid**. The money may have moved. So the reconciler must **not guess**. Instead it exposes a gauge, `orders_stuck_confirmed`, counting `CONFIRMED`/`REFUND_PENDING` orders past a **longer** grace (default 10 minutes); an alert, `OrdersStuckConfirmed`, pages a human to look.

The principle the team wrote down: *a state where **no money has moved** (PENDING, FAILED) is safe to auto-cancel; a state where it **might have** (CONFIRMED, REFUND_PENDING) is only alerted on.*

---

## Questions

### A — Literal
1. Why is `placeOrder` deliberately not one big `@Transactional` method?
2. What one property of `release(orderRef)` makes it safe for the reconciler to call on a `FAILED`/`PENDING` order whose reservation outcome is unknown?
3. When inventory is still down during a reconcile batch, what does the reconciler do with the rest of the batch, and why?

### B — Inference
4. `FAILED` and a long-stuck `PENDING` are handled the same way. From the passage, why are they the "same shape" of problem despite reaching that state by different paths?
5. The grace period for auto-cancel (`FAILED`/`PENDING`) is ~2 minutes but the stuck-gauge grace (`CONFIRMED`) is ~10 minutes. Why is the second one deliberately **longer**?

### C — Trade-off
6. Auto-cancelling a stuck `CONFIRMED` order would make the system "self-healing" with no human in the loop. The team chose **not** to. What is the specific risk they are refusing to take, and what do they accept instead?
7. The reconciler stops the whole batch on the first inventory failure instead of skipping that order and continuing. What does stopping protect, and what's the downside if one specific order can never be released?

### D — Transfer / extension
8. Suppose payment-service is healthy but a **Kafka consumer-lag** spike means `PaymentCaptured` events are delayed by 15 minutes. Walk through what the operator sees (which alert, which orders) and why **nothing should be auto-cancelled**.
9. You're asked to add an **orchestrated saga timeout** so genuinely orphaned `CONFIRMED` orders (payment-service truly lost the event) don't sit forever. Using the passage's principle, what must that timeout do **before** it cancels, so it doesn't strand a paid order?

### E — Spot-the-flaw
10. A dev "simplifies" the reconciler to also auto-cancel `CONFIRMED` orders older than 2 minutes "to clear limbo faster." Describe the exact production incident this causes, step by step, for an order that was actually paid.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. Because a single `@Transactional` method would hold the DB transaction **and its pooled connection** open across the **remote HTTP call** to inventory-service. Under load that exhausts the connection pool (every in-flight order parks a connection for a whole network round-trip), and the service stalls. Saving each step separately frees the connection during the call; the order **status** records progress instead.
2. **`release` is idempotent** — releasing an order that reserved nothing is a **no-op**. So even when the reservation outcome is unknown, calling `release` can't double-release or corrupt stock; it either gives back what was reserved or does nothing.
3. It **stops** the batch at the first failure and retries on the next 60s run. Hammering an already-down dependency with the rest of the batch just adds load and won't succeed; waiting a minute is cheaper and self-correcting.
</details>

<details>
<summary>B — Inference</summary>

4. Both are **"outcome unknown"** with **no money moved**. `FAILED` = we called inventory but never got an answer (timeout/breaker). Long-`PENDING` = the pod died before we even recorded the inventory outcome. In both, we don't know if stock was reserved, and payment has definitely not happened — so the safe resolution is identical: idempotent `release` + `CANCELLED`.
5. A stuck `CONFIRMED` order is *expected* to resolve on its own once payment-service replies; most "stuck" ones at 2 minutes are just **normal latency** (consumer lag, a pod restart). A short grace would page humans constantly for things that fix themselves. The longer 10-minute grace means the alert only fires for orders that are **genuinely** not progressing, cutting false pages.
</details>

<details>
<summary>C — Trade-off</summary>

6. The risk: **auto-cancelling an order that was actually paid.** A stuck `CONFIRMED` order may be one payment-service is about to confirm; cancel it and you've cancelled a paid order (customer charged, order gone) — a money-correctness bug you can't cheaply undo. They refuse that risk and **accept** instead a human-in-the-loop delay: a gauge + `OrdersStuckConfirmed` alert, so a person decides. Slower, but never wrong about money.
7. Stopping protects against **wasting effort and adding load on a down dependency**, and keeps retries orderly. The downside: a **single poison order** that can never be released (e.g. a permanently bad `orderRef`) would block the batch behind it every run — head-of-line blocking — so you'd eventually want to skip-and-park that specific order rather than stall the whole sweep.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. The operator sees `KafkaConsumerLagHigh` on group `payment-service` (the root cause) and, if the lag lasts past ~10 minutes, `OrdersStuckConfirmed` climbing (the symptom: orders waiting in `CONFIRMED`). **Nothing should be auto-cancelled** because those orders are *about to* be paid — the events exist, they're just delayed. Cancelling them would cancel orders that will be paid seconds later. The fix is to clear the lag (scale consumers, unblock a poison record), after which the gauge drains on its own.
9. Before cancelling, the timeout must **confirm no payment was captured for that order** — i.e. ask payment-service (or check a shared source of truth) whether a `payment` row in `CAPTURED` exists for that `orderRef`. Only an order with **no captured payment** is safe to cancel; if one exists, it must drive the saga forward (mark `PAID`/refund) instead. The principle holds: never cancel where money might have moved without first proving it didn't.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Order X reserves stock → `CONFIRMED` → `OrderConfirmed` emitted. payment-service is briefly lagging (pod restart), so `PaymentCaptured` is delayed by ~3 minutes. At minute 2, the "simplified" reconciler sees X still `CONFIRMED` and auto-cancels it: `release` stock + `CANCELLED`. At minute 3, payment-service's charge **succeeds** and emits `PaymentCaptured` — but order-service's `onPaymentCaptured` only moves `CONFIRMED` orders, and X is now `CANCELLED`, so it's a no-op. Result: **the customer's card was charged, the stock was given away, and the order says CANCELLED** — money lost, no product, no record. It violated the principle: `CONFIRMED` is a "money might have moved" state and must never be auto-cancelled.
</details>

---

## Tod ke dekho — Local (docker compose)

Goal: create a `FAILED`/limbo order by taking inventory down, watch the reconciler settle it; and watch a stuck-`CONFIRMED` gauge **not** get auto-cancelled.

```bash
make up && TOKEN=$(make -s token)

# A) LIMBO: inventory down -> order saved FAILED, readiness still UP
docker compose -f shopflow/docker-compose.yml stop inventory-service
ID=$(curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' | jq -r '.id // .status')
curl -s http://localhost:8081/actuator/health/readiness | jq   # still UP (inventory excluded)

# B) RECONCILER settles it: bring inventory back, wait past the 2m grace + a 60s tick
docker compose -f shopflow/docker-compose.yml start inventory-service
# (speed it up by lowering grace: set env orders.reconcile.grace=10s on order-service and restart, or just wait ~3 min)
sleep 180
curl -s http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" | jq '.status'   # -> "CANCELLED" (no limbo)

# C) STUCK CONFIRMED gauge: stop payment-service, place a normal order -> sits CONFIRMED
docker compose -f shopflow/docker-compose.yml stop payment-service
curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' >/dev/null
# Prometheus (http://localhost:9090) -> query:  orders_stuck_confirmed   (climbs after the 10m grace)
# It is NEVER auto-cancelled. Bring payment back -> it reaches PAID on its own:
docker compose -f shopflow/docker-compose.yml start payment-service
```

**Proved:** FAILED/PENDING limbo auto-resolves to CANCELLED safely; CONFIRMED limbo is only *counted* (gauge/alert), never auto-cancelled — because money might have moved.

> Tip: grace values are configurable (`orders.reconcile.grace:2m`, `orders.stuck.grace:10m`, `orders.reconcile.delay:60s` in `OrderReconciler`). Lower them via env to see it fast.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — order+inventory+payment+prometheus chahiye.

---

## Interview concepts is passage se
- **Connection-pool exhaustion** — DB txn ko remote call ke aar-paar mat rakho.
- **Status as saga progress** — row ka state hi "kahan tak pahunche" record karta.
- **Limbo / outcome-unknown states** aur **reconciliation loop**.
- **Idempotent compensation** (`release` no-op) jo blind retry ko safe banata.
- **"Not every limbo auto-cancels"** — money-moved vs not; human-in-the-loop alert.
- **Don't hammer a down dependency** — batch stop + retry; backoff thinking.

**Interviewer's lens:** kya tu "self-healing" ka lalach rok ke money-correctness choose kar pata hai, aur paid-order-cancel wala incident step-by-step bol pata hai. Yahi "senior judgement" wala sawaal hai.

> Interview line: *"Main remote call ke around transaction nahi rakhta — pool exhaust ho jaata; status se saga progress track hota. FAILED/PENDING limbo (no money moved) ko reconciler har minute idempotent release se CANCELLED kar deta. Par CONFIRMED stuck ko main auto-cancel nahi karta — payment in-flight ho sakta, cancel karte to paid order cancel ho jaata — isliye wo sirf gauge + alert pe jaata, human decide karta."*
</content>
