# D12 · The dead-letter topic has 9,000 records and nobody noticed

> Skill: async / dead-letter triage. Related mechanism: [`../comprehension/11-kafka-dlt-lag/`](../comprehension/11-kafka-dlt-lag/), [`../comprehension/03-idempotency-three-ways/`](../comprehension/03-idempotency-three-ways/).

---

## Page (symptom)

Finance flags that ~1% of `PAID` orders over the last three days were **never emailed** a confirmation, and a handful were **charged but the order still shows CONFIRMED, not PAID**. Dashboards are green: consumer lag on every group is ~0, no alerts, no 5xx. Then someone checks the topics: **`payments.events.DLT` has 9,000 records and is growing**, and `orders.events.DLT` has a few thousand too. They've been accumulating since a deploy three days ago.

**Before details: lag is zero and nothing is alerting, yet records are being lost. How is that possible, and where is the failure hiding?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- All consumer groups: lag ~0 (the main topics are being consumed fine).
- `payments.events.DLT` depth: 9,000 and climbing ~3,000/day since a payment-service deploy 3 days ago.
- Sample a few DLT records: they're real `PaymentCaptured` events, but the payload is missing the `amount` field (the deploy **renamed** `amount` → `amountMinor`), so order-service's consumer reads `amount` as null and `onPaymentCaptured` throws downstream.
- order-service consumers use `@JsonIgnoreProperties(ignoreUnknown = true)` — so *added* fields are tolerated, but a *renamed/removed* required field reads as null.
- There is **no alert** on DLT depth; there's an alert on consumer *lag*, which is 0.
- Each DLT'd `PaymentCaptured` = an order that was charged but never moved to `PAID`.
</details>

---

## Your move

1. Lag is 0 and nothing alerts, yet events are being lost. Explain how a *working* dead-letter mechanism produces exactly this "silent loss".
2. Why is lag 0 even though thousands of records "failed"? What did the error handler do that keeps the main partition flowing?
3. The consumers use `ignoreUnknown = true`, which is supposed to make schemas evolve safely. Why didn't it protect against *this* change?
4. You've found the cause (a renamed field). What are the **two** things you must do — not one — to actually recover, and in what order?
5. What monitoring was missing, and what alert would have caught this on day one instead of day three?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**How "working as designed" loses data (Q1).** The dead-letter path is doing its job: a record that fails processing is retried, then moved to `<topic>.DLT` so it **doesn't block the partition**. That's good for availability — but a DLT is a **quarantine, not a resolution**. Every record parked there is a business action that **didn't happen** (an email unsent, an order not marked PAID). If nothing **watches the DLT**, the records rot there silently: no lag (they're off the main topic), no 5xx (consumption "succeeded" by dead-lettering), no alert. The failure is hiding **inside the success of the error handler**.

**Why lag is 0 (Q2).** The `DefaultErrorHandler` retried each bad `PaymentCaptured` with backoff, then the `DeadLetterPublishingRecoverer` published it to `payments.events.DLT` and **committed the offset** on the main topic. So the main partition keeps advancing — lag stays 0 — precisely *because* the bad records were shunted aside. Lag measures the main topic, not the graveyard; a growing DLT with zero lag is the signature of "the escape hatch is busy."

**Why `ignoreUnknown` didn't help (Q3).** `@JsonIgnoreProperties(ignoreUnknown = true)` tolerates **added** fields — a forward-compatible change. But this deploy **renamed** `amount` → `amountMinor`, which to the old consumer looks like "the `amount` field **disappeared**" → it deserializes to **null** → downstream (`onPaymentCaptured` / amount math) throws → the record is dead-lettered. Ignore-unknown protects against *additions*, not *renames/removals* of fields a consumer depends on. This is exactly the gap a **Schema Registry** with compatibility checks (STATE §4 open item) closes: it would have **rejected** the incompatible producer schema at deploy time instead of letting it poison consumers at runtime.

**Recovery is two steps, in order (Q4).** (1) **Fix the cause** — ship the producer/consumer fix so new events are compatible again (stop the bleeding; the DLT stops growing). (2) **Replay the DLT** — reprocess the 9,000 parked records back onto the main topic (or through a repair job) so the charged-but-unconfirmed orders finally move to `PAID` and the missing emails go out. Order matters: replaying **before** fixing the cause just dead-letters them again. And because the consumers are **idempotent** (`processed_event` / `onPaymentCaptured` is status-guarded to only act on `CONFIRMED`), replaying is **safe** — already-processed events are no-ops, so you can replay the whole DLT without double-charging or double-emailing.

**What was missing (Q5).** An **alert on DLT depth / DLT publish rate** (e.g. `rate(dead_letter_records) > 0 for 10m`, or `kafka_topic_dlt_records > N`). The existing alert watches **consumer lag**, which this failure keeps at 0 by design — so the one metric being watched was blind to it. A DLT without an alert and an owner is just a slower, quieter way to lose messages. (Pair it with a **runbook**: inspect a sample, identify the pattern, fix, replay.)
</details>

---

## Reproduce / test (apne haath se)
```bash
make up
# produce a few malformed records (like D08) and watch the DLT depth grow while main-group lag stays 0:
for i in 1 2 3; do echo "bad-$i" | docker compose -f shopflow/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic payments.events; done
sleep 12
docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payments.events.DLT --from-beginning --timeout-ms 5000   # records piling up here
docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group order-service          # LAG ~0 (the escape hatch keeps the main topic flowing)
# the gap: there is no DLT-depth alert -> add one (alert on the escape hatch).
```

## The pattern

- **A growing DLT with zero lag = the error handler is silently eating a systematic failure.** Lag watches the main topic; the DLT is a separate graveyard you must watch separately.
- **A dead-letter topic is a quarantine, not a fix.** It buys availability (partition keeps flowing) by deferring the problem — which is only a win if something **alerts, triages, and replays**.
- **`ignoreUnknown` ≠ schema safety.** It tolerates added fields, not renamed/removed ones. Only a registry with compatibility enforcement actually prevents incompatible producers.
- **Recovery is fix-then-replay, never replay-first.** And it's only safe to replay because consumers are idempotent — which is why idempotency is load-bearing, not optional.
- **Alert on the escape hatch.** Any mechanism that makes failures invisible (DLT, retry, fail-open) needs its own metric and alert, or it becomes a blind spot.

> Grounded in: `KafkaErrorConfig` (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → `<topic>.DLT`), the `@JsonIgnoreProperties(ignoreUnknown=true)` event copies (LLD anti-corruption), idempotent consumers (`processed_event`, status-guarded `onPaymentCaptured`). Schema Registry = STATE §4 open item.
</content>
