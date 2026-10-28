# 01 · Dual write & the transactional outbox — comprehension

> Companion concept notes: [`notes.md`](notes.md). Grounded in: `shopflow/shopflow-outbox/…/OutboxRelay.java`, `OutboxPublisher.java`, `order-service/…/OrderService.java` (`saveWithEvent`).
> **How to use:** passage 2–3 min padho, phir answers likho, phir `<details>` kholo. Rubric: [`../README.md`](../README.md).

---

## Scenario (read cold)

> *Excerpt from a design review of ShopFlow's order-service. Read it once, then answer.*

When a customer places an order, two things must happen: the order row is written to Postgres, and an `OrderConfirmed` event is published to Kafka so payment-service and notification-service can react. An early version did both directly inside one method:

```java
orderRepository.save(order);                 // write #1: Postgres
kafkaTemplate.send("orders.events", event);  // write #2: Kafka
```

This looked fine in testing and broke in production. The two writes are to **two different systems**, and there is no transaction that spans both. If the pod crashed, or Kafka was briefly unreachable, after `save` but before `send`, the order existed but **no event was ever emitted** — payment-service never charged, notification-service never emailed, and the order sat silently unpaid. Reordering the two lines only moves the hole: publish-then-save can emit an event for an order that was never persisted.

The team replaced it with a **transactional outbox**. `OrderService.saveWithEvent` now writes the order row **and** a row in an `outbox_event` table in the **same local Postgres transaction** (via a `TransactionTemplate`). Because both rows are in one DB transaction, they commit together or not at all — the dual write is gone.

A separate component, `OutboxRelay`, is `@Scheduled` to run every second. It reads up to 100 unpublished rows, and for each one calls `kafkaTemplate.send(...).get(5, SECONDS)` — it **waits for the broker ack** before calling `markPublished()`. If a send fails, it logs and **returns immediately**, so the rest of the batch is retried on the next run, and events keep their order per key. A nightly job deletes published rows older than 7 days; unpublished rows are never deleted. A gauge, `outbox_unpublished`, counts rows where `published_at IS NULL`; an alert fires if it stays above 100 for 5 minutes.

The relay guarantees **at-least-once** delivery: if the pod dies between a successful `send` and `markPublished`, that row is published again next run. The design doc notes this is deliberate, and adds one line: *"therefore every consumer must be idempotent."*

---

## Questions

### A — Literal (passage mein likha hai)
1. The original code made two writes. Name the two systems they went to, and the exact window in which a crash loses the event.
2. What does the relay do **before** it calls `markPublished()` on a row, and why does the passage say that step matters?
3. Which outbox rows are never deleted by the cleanup job?

### B — Inference (reason karo)
4. The passage says publishing is *at-least-once*, not *exactly-once*. From the relay's crash scenario, explain why a single event can reach Kafka **twice**.
5. `outbox_unpublished` suddenly climbs past 100 and the alert fires, but `POST /orders` is still returning `201`. What does that combination tell you is broken, and what is **not** broken?

### C — Trade-off (judgement)
6. The relay waits up to 5 seconds for each broker ack (`.get(5, SECONDS)`) instead of fire-and-forget. What does this buy, and what does it cost under load?
7. On a send failure the relay `return`s and abandons the rest of the batch instead of skipping the bad row and continuing. Why would "keep ordering" justify stopping, and when would this choice hurt you?

### D — Transfer / extension (naya scenario)
8. Your teammate proposes dropping the outbox and instead using **Debezium** to stream the `orders` table's commit log straight to Kafka. Give one concrete advantage and one cost of that versus the polling relay.
9. A new `OrderCancelled` event must also reach notification-service. Using only what the passage describes, what is the **complete** change needed in the producing service — and what must you check on the consumer side?

### E — Spot-the-flaw
10. A junior rewrites `saveWithEvent` to use `@Transactional` on a **private** method in the same class, called from `placeOrder()` in that same class. The code compiles and tests on the happy path pass, but in production the order and outbox rows sometimes commit separately. What is the bug, and why did happy-path tests miss it?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Postgres** (the order row) and **Kafka** (the `OrderConfirmed` event). The loss window is **after `save` succeeds but before `send` completes** — a crash or a Kafka blip there leaves a persisted order with no event.
2. It **waits for the broker's ack** (`.get(5, SECONDS)`). It matters because without waiting you'd call `markPublished()` on a message the broker may never have stored — you'd mark a lost message as sent, turning at-least-once into "maybe-never."
3. **Unpublished** rows (`published_at IS NULL`). Only *published* rows older than 7 days are deleted; an event that never made it to Kafka is kept forever so it is never silently dropped.
</details>

<details>
<summary>B — Inference</summary>

4. The relay does `send` → (ack received) → `markPublished`. If the pod **dies after the ack but before `markPublished` commits**, the row is still `published_at IS NULL` next run, so the relay sends it **again**. The broker already has copy #1; now it gets copy #2 → duplicate.
5. The write path (Postgres + outbox row in one txn) is **healthy** — that's why `POST /orders` still returns 201. What's broken is the **relay → Kafka leg**: Kafka is down/unreachable, or the relay pod is stuck, so rows pile up unpublished. Customers' orders are safe and durable; downstream (payment, notification) is just **delayed**, and will catch up once the relay drains. This is exactly the failure the outbox is designed to survive.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Buys:** correctness — you only mark a row published once a broker actually has it (`acks=all` on the producer means every in-sync replica has it), so you never lose an acknowledged event. **Costs:** throughput/latency — each send is synchronous, so a slow broker makes the 1-second relay batch take longer, and the backlog can grow during a Kafka slowdown. It trades raw speed for not-losing-data, which is the right trade for an order event.
7. **Why stop:** rows for the same aggregate (`orderRef` key) must reach the partition in order; if you skipped a failed row and sent later ones, a consumer could see `OrderPaid` before `OrderConfirmed`. Stopping preserves per-key order and lets the next run retry from the gap. **When it hurts:** a single genuinely poison row (e.g. a payload Kafka rejects) blocks the whole backlog behind it — head-of-line blocking. Then you need a DLT/park-row escape so one bad event can't freeze everything.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Debezium (log-based CDC) advantage:** no polling and no application code in the write path — it tails Postgres's WAL, so there's near-zero added latency and nothing to mark/clean up; the "event" is just the committed row. **Cost:** operational weight — you run Kafka Connect + Debezium, manage connector offsets/schemas, and your events now mirror table shape (or need an outbox-table pattern anyway via the Debezium *outbox router*). The polling relay is a few lines of app code with no extra infra; Debezium is a platform component. (Both are at-least-once; both still need idempotent consumers.)
9. **Producer side (complete change):** nothing structural — call `saveWithEvent` (or the same `outbox.publish(...)`) with event type `OrderCancelled` inside the same local transaction that flips the order's status. The relay already publishes **any** row generically (topic, key, payload), so it needs no change. **Consumer side:** notification-service must (a) handle the new `type` in its switch, and (b) because delivery is at-least-once, **dedupe** it (it already keys `processed_event` by `eventId`), so a redelivered `OrderCancelled` doesn't email twice.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `@Transactional` is **proxy-based**. Spring wraps the bean in a proxy that opens the transaction; a **self-invocation** (one method in the class calling another method *in the same class*) bypasses the proxy, so the annotation does nothing and the inner method runs **without a transaction** — the order `save` and the outbox `save` can then commit independently. Happy-path tests missed it because with no crash the two saves still both happen and look committed together; the gap only shows under a failure **between** them. This is precisely why ShopFlow uses a **`TransactionTemplate`** (works regardless of how it's called) and not `@Transactional`, and the code comment says so.
</details>

---

## Tod ke dekho — Local (docker compose)

Goal: prove the outbox **decouples the durable write from the Kafka publish** — kill Kafka, the order still saves, the backlog grows, and it drains on recovery.

```bash
make up                                   # full stack (wait ~2-3 min: docker compose ps -> healthy)
TOKEN=$(make -s token)                     # JWT for alice

# 1) BREAK: stop Kafka, keep everything else running
docker compose -f shopflow/docker-compose.yml stop kafka

# 2) Place an order anyway -> still 201 (Postgres + outbox row committed; Kafka not needed yet)
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PS5-SLIM","quantity":1}' | jq '.status,.orderRef'

# 3) OBSERVE: the outbox row is unpublished, and the backlog gauge climbs
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U orders -d orders -c \
  "select id, event_type, published_at from outbox_event order by id desc limit 5;"
#   -> newest row has published_at = NULL
# Prometheus (http://localhost:9090) -> query:  outbox_unpublished   -> goes up
# Leave it >5 min and OutboxBacklogGrowing fires (http://localhost:9093 alertmanager)

# 4) FIX: bring Kafka back -> relay drains within a couple of 1s batches
docker compose -f shopflow/docker-compose.yml start kafka
sleep 10
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U orders -d orders -c \
  "select count(*) as still_unpublished from outbox_event where published_at is null;"
#   -> 0 ;  outbox_unpublished back to 0 ; notification-service now processed it
docker compose -f shopflow/docker-compose.yml logs --tail=20 notification-service
```

**What you just proved:** the 201 in step 2 with Kafka *down* is the whole point — the order was never at risk, only its downstream event was delayed, and the relay delivered it later (at-least-once). That is the answer to "what if Kafka is down when an order comes in?"

> Extra break: scale the relay off and watch it stop draining, or `docker compose pause kafka` mid-load to see the gauge ramp. Related break/fix lab: `docs/LABS.md` (outbox / Kafka-down labs).

## Tod ke dekho — Online

Laptop pe Docker nahi? → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (GitHub Codespaces)**: wahi `make up` + ye saari commands browser mein chalti hain. Prometheus/Grafana ko Ports tab se public karke `outbox_unpublished` graph dekh. (Kafka chahiye, isliye Killercoda/Cloud-Shell nahi — Codespaces.)

---

## Interview concepts is passage se

- **Dual-write problem** — do systems ko atomically update nahi kar sakte; crash = inconsistency.
- **Transactional outbox** — business row + event row ek hi DB txn; relay async publish.
- **Polling publisher vs CDC (Debezium)** — app-code vs infra; dono at-least-once.
- **At-least-once + idempotent consumer** — duplicate aana normal, consumer ko dedupe karna hai (`processed_event`/PK).
- **"Exactly-once" myth** — DB/email loop mein exactly-once nahi hota; at-least-once + idempotency hi real answer.
- **Head-of-line blocking & ordering per key** — stop-on-failure se order bachta hai par poison row sabko rok sakta hai → DLT.
- **Backlog alerting** — `outbox_unpublished` gauge → "publish leg down" ka early signal jabki API abhi bhi 201 de raha.

**Interviewer's lens:** wo dekh raha hai ki tu (a) crash window identify kar pata hai, (b) "at-least-once isliye consumer idempotent" ka link khud banata hai, (c) Debezium jaisa alternative weigh kar pata hai bina "better/worse" ke — "kab kaunsa" bol pata hai.

> Interview mein ek line: *"Hum DB aur Kafka ko ek saath atomically nahi likh sakte, isliye order aur uska event ek hi Postgres transaction mein outbox table mein jaate hain; ek relay har second unhe publish karta hai, broker ack ka wait karke mark karta hai. Delivery at-least-once hai, isliye har consumer `eventId` pe dedupe karta hai."*
</content>
