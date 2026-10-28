# 11 · Poison pills, dead-letter topics & consumer lag — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `KafkaErrorConfig.java` (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`), `order-service application.yml` (consumer config), `PaymentEventListener`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's Kafka reliability notes. Read once, then answer.*

Kafka delivers records from a partition **in order**, one consumer in a group owning each partition. That creates a sharp failure mode: a **poison pill** — a single record that **always** fails to process (a malformed payload, a bug for that one case) — would be retried forever and **block its entire partition**, because the consumer can't commit past it. Every order behind it on that partition stops moving.

ShopFlow installs a `DefaultErrorHandler` with two parts. First, **retry with backoff**: `ExponentialBackOff(500ms, ×2)` capped at a **10-second** max elapsed time — so a transient hiccup (a brief DB blip while handling the event) gets a few quick retries. If the record still fails after that, a **`DeadLetterPublishingRecoverer`** moves it to a **dead-letter topic** (e.g. `payments.events.DLT`) and the consumer **commits past it**, so the partition keeps flowing. The poison pill is quarantined for a human to inspect, not left to block live traffic.

Consumers are configured for safety: `enable-auto-commit: false` (offsets commit only after the listener's transaction succeeds, so a crash mid-processing re-delivers rather than skips), `isolation.level: read_committed` (only see records from committed producer transactions), and `auto-offset-reset: earliest` (a brand-new consumer starts from the beginning, not "now").

**Ordering** is guaranteed only **within a partition**, so related events must share a **key** — ShopFlow keys by `orderRef`, so every event for one order lands on the same partition and is seen in order. A consequence: if you ever change the partition count, the key→partition mapping changes and ordering guarantees across the change break.

**Consumer lag** — how far behind the latest record a group is — is the key health metric. If the `payment-service` group lags, orders sit `CONFIRMED` with stock reserved but unpaid; an alert, `KafkaConsumerLagHigh`, fires. The first thing to check is whether a **poison-pill retry loop** is burning the consumer's time. You reduce lag by: consumers ≤ partitions (you can't parallelize beyond partition count — 3 locally, 6 on MSK), then faster processing, then more partitions.

Finally, true **exactly-once** across Kafka **and a database/email** doesn't exist; ShopFlow uses **at-least-once + idempotent consumers** (`processed_event` dedupe), which is the real-world equivalent.

---

## Questions

### A — Literal
1. What is a "poison pill," and what would it do to its partition without an error handler?
2. What two things does the `DefaultErrorHandler` do, in order, and where does an unrecoverable record end up?
3. Why is `enable-auto-commit` set to `false`?

### B — Inference
4. The backoff is capped at 10 seconds total, *then* the record is dead-lettered. Why cap it instead of retrying indefinitely until it succeeds?
5. `payment-service`'s consumer group is lagging. Walk through the concrete business consequence for orders, step by step.

### C — Trade-off
6. Dead-lettering keeps the partition flowing but **skips** the bad record. What's the risk of moving on, and what must you have in place so a DLT'd record isn't silently lost?
7. ShopFlow accepts at-least-once (+ idempotent consumers) instead of chasing exactly-once. What does that trade away, and why is it the pragmatic choice when a DB and emails are in the loop?

### D — Transfer / extension
8. Lag on `payment-service` is climbing and there are **3** partitions with **3** consumer instances already. Your teammate says "scale consumers to 6." Why won't that help as-is, and what's the correct sequence of fixes?
9. A new requirement: events for the same **customer** (across different orders) must be processed in order. What has to change about the **key**, and what new risk does that introduce for partition balance?

### E — Spot-the-flaw
10. To "handle errors," a dev wraps the listener body in `try/catch` that logs the exception and returns normally (swallowing it). Tests pass. What two protections — one about the partition, one about correctness — have been silently destroyed, and what happens to a transiently-failed event?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. A record that **always fails to process** (malformed payload, a bug specific to that case). Without an error handler it would be **retried forever**, and since the consumer can't commit past it, it **blocks the whole partition** — every record behind it stops being processed (head-of-line blocking).
2. **(1)** Retry with exponential backoff (`500ms ×2`, capped at 10s total); **(2)** if still failing, a `DeadLetterPublishingRecoverer` publishes the record to a **dead-letter topic** (`<topic>.DLT`) and the consumer commits past it. The unrecoverable record ends up on the **DLT**.
3. So offsets are committed **only after the listener's transaction succeeds**. With auto-commit, a crash *after* the offset commit but *before* processing finishes would **skip** the event (lost); manual commit after success means a crash **re-delivers** it instead (at-least-once), which idempotent consumers handle safely.
</details>

<details>
<summary>B — Inference</summary>

4. Retrying forever just **recreates the poison-pill block** — a record that will *never* succeed would hold the partition indefinitely while you retry. The cap converts "block forever" into "try a few times for a transient issue, then quarantine and move on," keeping live traffic flowing. Transient problems usually clear within seconds; anything that doesn't is a real defect that belongs in the DLT for a human, not in an infinite loop.
5. payment-service lags → `OrderConfirmed` events pile up unconsumed → payment-service doesn't charge those orders → they stay **`CONFIRMED`** in order-service (stock already reserved) → no `PaymentCaptured` comes back → orders are **reserved-but-unpaid**, and if the lag lasts past the grace, the `orders_stuck_confirmed` gauge climbs and `OrdersStuckConfirmed` alerts. Revenue is delayed and stock is held for orders that haven't paid.
</details>

<details>
<summary>C — Trade-off</summary>

6. The risk is **data/event loss of meaning**: a dead-lettered `PaymentCaptured` means an order that was actually paid never gets marked `PAID` unless someone acts. So you must have **(a)** monitoring/alerting on DLT depth, **(b)** a documented **reprocess/replay** path (fix the bug or data, replay the DLT back onto the main topic), and **(c)** ownership so the DLT is actually triaged. A DLT nobody watches is just a slower way to lose messages.
7. It trades away the *illusion* of "every event processed exactly one time." You accept that an event can be **delivered more than once**, which is why consumers must be **idempotent** (`processed_event` dedupe, PK = eventId). It's pragmatic because exactly-once only exists **Kafka→Kafka** (idempotent producer + transactions); the moment you also write to Postgres or send an email — systems outside Kafka's transaction — you can't make "process + external side effect + commit offset" atomic, so at-least-once + idempotency is the honest, achievable guarantee.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. With **3 partitions**, at most **3** consumers in the group can be active — the extra 3 would sit **idle** (a partition is owned by exactly one consumer in the group), so scaling to 6 does nothing. Correct sequence: **(1)** rule out a **poison-pill retry loop** burning time (check the DLT / error logs); **(2)** make processing **faster** (batch DB writes, remove slow calls); **(3)** only then **increase partitions** (e.g. to 6) and scale consumers to match — accepting that repartitioning changes key→partition mapping and briefly disrupts ordering.
9. The **key must change from `orderRef` to `customerId`** so all of a customer's events hash to the same partition and stay ordered. **New risk:** partition **imbalance / hot partitions** — a few very active customers would concentrate load on specific partitions (and you lose per-order parallelism), whereas `orderRef` spread load evenly. You've traded broader ordering for worse balance.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Swallowing the exception and returning normally makes Spring think the record **processed successfully**, so it **commits the offset** and moves on. Destroyed: **(1) the DLT/retry safety net** — the `DefaultErrorHandler` never sees the exception, so nothing is retried or dead-lettered (the partition keeps flowing, but only because every failure is silently accepted); **(2) correctness/at-least-once** — a **transiently** failed event (DB blip) is now **permanently skipped** instead of retried, so that `PaymentCaptured` is lost and the order never becomes `PAID`, with no DLT record to recover from. The listener must let the exception **propagate** so the error handler and offset-management can do their jobs.
</details>

---

## Tod ke dekho — Local (docker compose + kafka CLI)

Goal: watch consumer groups, lag, and a dead-letter topic.

```bash
make up && TOKEN=$(make -s token)
KT() { docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/"$@"; }

# 1) place a few orders to generate events
for i in $(seq 1 5); do curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' -o /dev/null; done

# 2) list topics (note orders.events / payments.events and any .DLT) and consumer groups
KT kafka-topics.sh --bootstrap-server localhost:9092 --list
KT kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 3) LAG: stop payment-service, add orders -> payment-service group lag grows; orders sit CONFIRMED
docker compose -f shopflow/docker-compose.yml stop payment-service
for i in $(seq 1 5); do curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' -o /dev/null; done
KT kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group payment-service   # LAG column > 0
docker compose -f shopflow/docker-compose.yml start payment-service                                 # lag drains

# 4) POISON PILL -> DLT: hand-produce a malformed record to payments.events, watch it land on the DLT
#    (pipe host echo into the container's producer; -T disables the TTY so the pipe works.
#     Don't wrap in `KT bash` — KT prepends /opt/kafka/bin/, and there is no /opt/kafka/bin/bash.)
echo 'not-json-at-all' | docker compose -f shopflow/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payments.events
sleep 12   # order-service consumes it -> readValue fails -> retries (10s) then dead-letter
KT kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payments.events.DLT --from-beginning --timeout-ms 5000
```

**Proved:** lag on a stopped consumer group (orders stuck CONFIRMED), and a malformed record that retries then lands on `.DLT` while the partition keeps flowing.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — Kafka in compose; CLI inside the kafka container.

---

## Interview concepts is passage se
- **Partition ordering + consumer-per-partition** — parallelism ≤ partitions.
- **Poison pill & head-of-line blocking** — ek record poore partition ko rok de.
- **DefaultErrorHandler: retry+backoff → DLT** — quarantine, partition flow bana rahe.
- **Manual offset commit / read_committed / earliest** — at-least-once semantics.
- **Keying (`orderRef`) for ordering** — partition count change mapping torta.
- **Consumer lag** — health metric; lag = business delay; poison-loop pehle check.
- **Exactly-once myth** — DB/email ke saath at-least-once + idempotent consumer.

**Interviewer's lens:** "lag kaise kam karoge" ka sahi sequence (consumers≤partitions → faster → more partitions), aur DLT ke baad "lost na ho" ki responsibility (monitor + replay). Swallow-exception trap bhi.

> Interview line: *"Kafka partition ke andar order deta hai, ek consumer per partition. Ek poison pill poore partition ko block kar sakti thi, isliye DefaultErrorHandler: exp-backoff retry (10s cap) phir DeadLetterPublishingRecoverer record ko `.DLT` pe daal ke offset commit — partition chalti rehti. Offsets manual commit hote (auto-commit off) + read_committed, to at-least-once; consumer idempotent (processed_event). Ordering ke liye orderRef key. Lag business delay hai — payment group lag = orders CONFIRMED stuck; lag kam karne ka order: consumers≤partitions, phir faster processing, phir partitions badhao — par pehle poison-loop check."*
</content>
