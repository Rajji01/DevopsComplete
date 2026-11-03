# D08 · Consumer lag grows on ONE partition; the other two are fine

> Skill: Kafka partition-level debugging. Related mechanism: [`../comprehension/11-kafka-dlt-lag/`](../comprehension/11-kafka-dlt-lag/).

---

## Page (symptom)

`KafkaConsumerLagHigh` fires for the `payment-service` group. But when you look closer, it's **not** the whole group — `kafka-consumer-groups --describe` shows partition **2** with a lag of thousands and climbing, while partitions **0** and **1** sit at lag **0**. All three payment-service pods are `Running` and healthy; two of them are doing plenty of work. Some orders are stuck `CONFIRMED`; oddly, it seems to be a *subset* of orders, not all.

**Before details: lag on one partition but not the others is a very specific shape. What does it rule out, and what does it point straight at?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `kafka-consumer-groups --describe --group payment-service`: partition 0 lag 0, partition 1 lag 0, **partition 2 lag 8,400 and rising**.
- The consumer owning partition 2 logs the **same offset** over and over: `processing record at offset 48213 … failed … retrying`, every ~10s, never advancing past 48213.
- The exception is a `JsonParseException` from `objectMapper.readValue(payload, OrderEvent.class)` on one specific record — a **corrupt/truncated payload** that landed on partition 2 (a bad producer run, or schema drift) and can't be deserialized.
- No `orders.events.DLT` records appear; the topic doesn't exist and broker auto-create is off.
- Kafka, Postgres, the other two consumers: all healthy. Throughput on partitions 0 and 1 is normal.
</details>

---

## Your move

1. Lag on **one** partition, zero on the others, all consumers healthy — what does this immediately rule out (broker down? consumer crashed? slow dependency?), and what one thing does it point at?
2. The consumer logs the **same offset** repeatedly. What is that, in one word, and why does it block *this* partition but not the other two?
3. ShopFlow has a `DefaultErrorHandler` with a dead-letter recoverer — it's *supposed* to move a poison pill aside after retries. Why isn't it, here?
4. Two fixes: one that unblocks **right now**, one that stops it recurring. What's the risk of the "right now" one?
5. Why did only a *subset* of orders get stuck, and what does that tell you about the key?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**What the shape rules out (Q1).** A broker outage or a crashed consumer would stall **all** partitions (or reassign them). A slow dependency would slow **all** consumers roughly equally. Lag isolated to **one** partition with healthy consumers points at exactly one thing: a **poison pill** — a single record on partition 2 that fails every time, so the consumer can't commit past it, and everything behind it on *that* partition piles up. Partitions 0 and 1 have no such record, so they flow normally.

**The repeated offset (Q2).** That's the **stuck record** (offset 48213). Kafka delivers a partition's records **in order** and a consumer commits offsets sequentially, so it cannot move to 48214 until 48213 succeeds. It retries 48213 forever → head-of-line blocking, but **only on partition 2** — partitions 0 and 1 are owned by other consumers with their own offsets, unaffected.

**Why the DLT isn't saving it (Q3, the real root cause).** The `DefaultErrorHandler` *does* try to recover the poison pill: retry with backoff, then `DeadLetterPublishingRecoverer` publishes it to `orders.events.DLT` and commits past it. But here **the DLT topic doesn't exist and broker auto-create is off**, so the recoverer's own publish **fails** — it can't move the record aside, so the handler falls back to retrying it, forever. The safety net has a hole: *dead-lettering only works if the dead-letter topic is writable.* (The pill itself is a malformed event — a producer or schema-drift problem; a schema registry would stop it being produced at all.)

**Fixes (Q4):**
- **Right now (unblock):** create the `orders.events.DLT` topic (so the recoverer can move 48213 aside) — the partition immediately drains. Or, more bluntly, advance the consumer group's offset past 48213 for partition 2 (`kafka-consumer-groups --reset-offsets --to-offset 48214 --topic orders.events:2`). **Risk of the offset-skip:** you're **dropping** that record — the order it belonged to never gets charged and stays `CONFIRMED` forever unless you also reconcile it. Skipping data is a conscious trade, not a clean fix; prefer DLT (keeps the record for triage).
- **Recurring fix:** (a) make the DLT topic part of the deploy (auto-create or Terraform/MSK topic config) so the recoverer always has somewhere to go; (b) stop the malformed event at the source — validate/evolve the event schema (a **Schema Registry** with Avro/Protobuf is STATE §4's open item) so a corrupt or incompatible payload can't be produced in the first place.

**Why only a subset stuck (Q5).** Events are keyed by `orderRef`, so each order hashes to one partition; only orders whose `orderRef` landed on **partition 2** queue behind the poison pill. Orders on partitions 0/1 sail through. So "some orders stuck, not all" is the signature of a **per-partition** block, and it confirms the problem is one partition's head-of-line record, not a global outage.
</details>

---

## Reproduce / test (apne haath se)
```bash
make up
echo 'not-json-at-all' | docker compose -f shopflow/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic payments.events        # poison pill
sleep 12   # order-service retries (10s) then dead-letters
docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payments.events.DLT --from-beginning --timeout-ms 5000   # the record landed here
docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group order-service          # per-partition LAG column (one partition, not all)
```

## The pattern

- **Per-partition lag = a per-partition cause.** One partition hot, others cold, consumers healthy ⇒ a stuck record (poison pill), not a broker/consumer/dependency problem. Always read lag **per partition**, not just the group total.
- **A repeating offset in the logs is the poison pill's address.** The consumer telling you the same offset every few seconds *is* the diagnosis.
- **A dead-letter handler is only as good as its dead-letter topic.** If the `.DLT` can't be written (missing topic, auth, quota), the recoverer silently fails back to infinite retry — the net has a hole. Verify the escape hatch exists.
- **Skipping an offset drops data.** It unblocks fast but strands whatever that record represented; pair it with reconciliation, and prefer DLT when you can.
- **"Some, not all" is a routing clue.** A subset failing by key points at the partition the key maps to.

> Grounded in: `KafkaErrorConfig` (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`), `OrderConfirmedListener.onOrderEvent` (`objectMapper.readValue` → a malformed payload throws), `orders.events` keyed by `orderRef` (3 partitions local). Alert `KafkaConsumerLagHigh`; Schema Registry is a STATE §4 open item.
</content>
