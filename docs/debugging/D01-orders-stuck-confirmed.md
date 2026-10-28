# D01 · Orders pile up in CONFIRMED, PAID stays flat

> Skill: Kafka / async debugging. Related mechanism: [`../comprehension/11-kafka-dlt-lag/`](../comprehension/11-kafka-dlt-lag/), [`../comprehension/04-order-limbo-reconciler/`](../comprehension/04-order-limbo-reconciler/).

---

## Page (symptom)

It's 2am. Two things fire within a minute:

- Grafana: `orders_total{status="CONFIRMED"}` is climbing steadily; `orders_total{status="PAID"}` has been **flat for 12 minutes**.
- Alert: **`OrdersStuckConfirmed`** — the `orders_stuck_confirmed` gauge crossed its threshold.

Customers are completing checkout (they get a 201, order says CONFIRMED) but nothing is being charged. Stock is being reserved for orders that never pay.

**Before you open the details: write down what you check first, in order, and why.**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `orders_total{status="CONFIRMED"}` rising, `{status="PAID"}` flat, `{status="REJECTED"}` normal.
- order-service is healthy: readiness UP, 201s flowing, no 5xx.
- inventory-service healthy (reservations succeeding — that's why orders reach CONFIRMED).
- Kafka is up; the topic `orders.events` is receiving records.
- `kafka-consumer-groups --describe --group payment-service` → **LAG in the thousands and growing** on `orders.events`.
- payment-service: 1 of its 3 pods is in `CrashLoopBackOff`; the other 2 are "Running" but their per-pod consume rate is near zero.
- No recent deploy of order-service; payment-service was deployed ~15 min ago.
</details>

---

## Your move

1. The write path (order → CONFIRMED) works and readiness is UP. Which **leg** of the saga is therefore broken, and how do you confirm it in one command?
2. "PAID flat + CONFIRMED climbing" — translate that into **which consumer group** is behind and what that means for stock and money.
3. There was a payment-service deploy 15 min ago. What's your **first hypothesis**, and what single signal confirms or kills it?
4. You *could* just scale payment-service to 10 replicas. Why might that **not** help, and what must you check first?
5. Should the reconciler auto-cancel these stuck CONFIRMED orders to "clear the backlog"? Why or why not?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Bisect the path.** Orders reach CONFIRMED (order→inventory reserve over REST works) but never PAID. PAID only happens when order-service consumes `PaymentCaptured` from `payments.events`, which only happens after **payment-service consumes `OrderConfirmed` from `orders.events` and charges**. So the broken leg is **payment-service's consumption of `orders.events`**. Confirm in one command: `kafka-consumer-groups --describe --group payment-service` → **huge, growing lag**. That's the smoking gun.

**What does the lag mean?** Every lagging `OrderConfirmed` is an order sitting CONFIRMED with **stock reserved but not charged** — exactly what the climbing gauge and flat PAID show. This is revenue stalled and inventory locked up.

**"What changed?"** payment-service deployed 15 min ago — the lag started right after. First hypothesis: **the new version is broken or slow at consuming** (a bad config, a slow query, or it crashes on a specific record). The CrashLooping pod confirms part of it; the two "Running" pods consuming near-zero is the louder signal — they're alive but **not making progress**.

**Differential — why are the Running pods not progressing?** Two candidates with different predictions:
- (a) **Poison pill**: one record always fails → the consumer retries it and can't commit past it → lag grows while the pod looks busy. *Prediction:* error logs show the same record/offset retried repeatedly, and (eventually) a `.DLT` record; lag is stuck behind one offset per partition.
- (b) **Slow processing / dependency**: the new version added a slow call (e.g. a synchronous lookup) so each record takes seconds. *Prediction:* no repeated-offset errors, just low throughput and high per-record processing time.

Check the payment-service logs. Here: repeated `could not process … retrying` on the **same** offset → **(a) poison pill** in the new version (it throws on a record shape the old version tolerated), *and* the CrashLoop pod is OOMing/erroring on startup — both from the bad deploy.

**Root cause:** the 15-minute-old payment-service deploy is defective — it chokes on certain `OrderConfirmed` records (poison pill) and one pod crashes on boot. Consumption stalls → `orders.events` lag → orders stuck CONFIRMED.

**Fix (blast-radius-safe, in order):**
1. **Roll back** payment-service to the previous good version (`kubectl rollout undo` / Argo promote previous) — reversible, fastest path to draining the lag. Scaling the *broken* version to 10 pods would just give you 10 stuck consumers (and you can't exceed partition count anyway), which is why #4 "just scale" fails.
2. Once the good version consumes, the lag drains, orders flow CONFIRMED→PAID, the gauge falls.
3. The poison record(s): let the good version's error handler **DLT** them (or the old version handles them fine); triage the DLT separately.

**Confirm the fix:** lag on `payment-service` falls to ~0, `orders_total{status="PAID"}` starts rising, `orders_stuck_confirmed` drops, alert resolves.

**Why NOT auto-cancel the stuck CONFIRMED orders (Q5):** a CONFIRMED order may be about to be paid the moment the lag clears — auto-cancelling would cancel orders that are then charged (money moves, order gone). That's why the reconciler only *counts* them (gauge/alert) and a human decides; here the right action is "fix the consumer," not "cancel the orders." (See comprehension topic 04.)
</details>

---

## The pattern

- **A flat success metric + a climbing pending metric = a consumer/worker behind, not the producer.** The thing producing work is fine; the thing consuming it has stopped making progress.
- **"Running" ≠ "making progress."** A pod can be healthy on liveness and still consume at zero (poison-pill retry loop, slow dependency). Read **throughput/lag**, not just pod status.
- **Correlate with "what changed."** The deploy 15 min before the lag started is your first suspect; roll back first, diagnose the record later.
- **Scaling a broken consumer scales the breakage** (and never beyond partition count). Fix the version, don't multiply it.

> Grounded in: `payment-service/OrderConfirmedListener` (consumes `orders.events`), `order-service/OrderReconciler` (`orders_stuck_confirmed` gauge), `KafkaErrorConfig` (DLT). Alert `OrdersStuckConfirmed`.
</content>
