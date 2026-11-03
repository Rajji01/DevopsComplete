# 23 · MSK in prod: acks=all, RF3/minISR2, IAM auth — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `infra/terraform/aws/msk.tf`, `order-service/application.yml` (`acks: all`, `enable.idempotence: true`), `irsa.tf` (kafka-clients role). Pairs with [comp 01](../01-outbox-dual-write/), [comp 11](../11-kafka-dlt-lag/).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's prod messaging design. Read once, then answer.*

In dev, Kafka is a **single-node KRaft** broker in docker-compose — fine for local, but a single broker means **zero redundancy**: lose it and you lose in-flight events. In prod on AWS, ShopFlow uses **Amazon MSK** (managed Kafka), sized and configured for durability.

MSK runs **3 broker nodes across 3 availability zones**. The cluster config sets **`default.replication.factor=3`** and **`min.insync.replicas=2`**. The producers (order-service, payment-service) use **`acks=all`** with **`enable.idempotence=true`**. Put together: a write is **acknowledged only when at least 2 of the 3 replicas have it**. So **one broker — or one whole AZ — can fail without losing any acknowledged record**, and the idempotent producer means a producer retry won't create duplicates. This is the durability the outbox relay depends on: it only calls `markPublished()` after the broker **acks**, and that ack now means "2 replicas have it", not "one broker took it".

Other prod-hardening: **`auto.create.topics.enable=false`** (topics are created explicitly, so a typo'd topic name doesn't silently spawn a ghost topic), **`num.partitions=6`** (more consumer parallelism than dev's 3), **`log.retention.hours=168`** (7 days). Access is **IAM auth** — **no SASL passwords**: pods authenticate to MSK using their **IRSA** role (`kafka-cluster:Connect`, read/write scoped to `orders.events*`/`payments.events*`), and the MSK security group only allows the Kafka ports **from the EKS nodes**, inside private subnets.

The dev→prod contract is the same topics (`orders.events`, `payments.events`) and the same client code; only the **broker count, replication, auth, and partition count** change — all config/infra, not application code.

---

## Questions

### A — Literal
1. What's the durability difference between dev Kafka and prod MSK?
2. Spell out what `acks=all` + `min.insync.replicas=2` + RF3 together guarantee.
3. How do pods authenticate to MSK, and what are they allowed to do?

### B — Inference
4. The outbox relay marks a row published only after the broker **acks**. Why does `min.insync.replicas=2` make that ack *mean more* than it would with min-ISR 1?
5. `auto.create.topics.enable=false` in prod. What class of bug does that prevent that dev's auto-create would hide?

### C — Trade-off
6. `min.insync.replicas=2` with RF3 vs `min.insync.replicas=1`. What does the higher setting cost you (availability/latency), and why is it the right trade for order/payment events?
7. MSK (managed) vs self-managing Kafka on EC2. What does MSK buy, and what do you give up?

### D — Transfer / extension
8. One AZ goes down (one of the three brokers). Walk through what happens to producers and consumers with this config — do you lose data, and can you still write?
9. `min.insync.replicas=2` and RF3, but someone sets it so **2 brokers** are down at once. What happens to `acks=all` writes, and why is that the *intended* behaviour, not a bug?

### E — Spot-the-flaw
10. To "speed up checkout", a dev changes the order-service producer to `acks=1` ("just the leader, it's faster"). Latency drops. Months later, after a routine broker replacement, a handful of `OrderConfirmed` events are **gone** — orders stuck CONFIRMED, never paid. Explain the exact sequence, and why `acks=all` + minISR2 would have prevented it.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Dev**: a single-node broker — **no redundancy**; losing it loses in-flight events. **Prod MSK**: **3 brokers across 3 AZs** with replication, so a broker/AZ loss doesn't lose acknowledged events.
2. A producer write with `acks=all` is **acknowledged only when every in-sync replica has it**, and `min.insync.replicas=2` requires **at least 2 of the 3 replicas** to be in-sync for the write to succeed. So an acked record is on **≥2 brokers** — **one broker or AZ can fail and the record survives**.
3. Via **IAM auth** (no passwords): pods assume their **IRSA** role, which allows `kafka-cluster:Connect` and read/write **scoped by ARN** to `orders.events*`/`payments.events*` (and their own consumer groups). The MSK security group only accepts Kafka ports from the EKS nodes.
</details>

<details>
<summary>B — Inference</summary>

4. With `min.insync.replicas=1`, an `acks=all` write is acked as soon as **the leader alone** has it — if that leader then dies before a follower replicates, the "published" record is **lost**. With `min.insync.replicas=2`, the ack means **2 brokers** already have it, so the relay's `markPublished()` is safe against a single-broker loss. The ack is only as strong as min-ISR; raising it from 1→2 is what makes "acked = durable" true.
5. It prevents **silent topic typos / ghost topics**. With auto-create on (dev), a producer or consumer referencing a misspelled topic (`order.events` instead of `orders.events`) **silently creates** that topic with default settings — events flow into a topic nobody consumes, or a consumer reads an empty wrong topic, and it looks like "messages vanished." With auto-create **off**, that misconfiguration **fails loudly** instead of creating a ghost topic with (likely wrong) RF/partitions.
</details>

<details>
<summary>C — Trade-off</summary>

6. `min.insync.replicas=2` **costs availability for writes**: if only 1 replica is in-sync (2 brokers down, or replication lag), `acks=all` producers **can't write** (they get an error) rather than writing to a single copy. It also adds a little **latency** (wait for a second replica). For **order/payment events** that's the right trade — losing an acknowledged payment/order event is far worse than a brief write outage; you'd rather **stop writing than lose data**. (The outbox buffers the events durably in Postgres meanwhile, so the relay just retries.)
7. **MSK buys**: no brokers to patch/scale/replace, automated AZ spread, monitoring, IAM integration. **You give up**: fine-grained control and some cost efficiency — you can't tune everything, you're on MSK's versions/limits, and it's pricier than raw EC2. For a team that doesn't want to run Kafka, managed durability is worth it.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **One AZ/broker down**: RF3 means each partition still has **2 replicas** up, and `min.insync.replicas=2` is still satisfiable, so **producers can still write** (`acks=all` succeeds against the 2 in-sync replicas) and **consumers keep reading** (leadership fails over to a surviving replica). **No data loss** — every acked record was already on ≥2 brokers, and ≥2 are still alive. This is exactly what RF3/AZ-spread is for.
9. With **2 of 3 brokers down**, only **1 replica** is in-sync, which is **below `min.insync.replicas=2`**. So `acks=all` writes **fail** (producer gets `NotEnoughReplicas`) rather than writing to a single copy. That's **intended**: the config chooses **consistency/durability over availability** for writes — it refuses to accept a write it can't make durable, so you never have an "acked" record sitting on one fragile broker. Reads of already-committed data continue; new writes wait until a second replica is back (the outbox holds them meanwhile).
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `acks=1` means the producer is acked as soon as the **leader** has the record — **before** any follower has replicated it. Sequence: the relay publishes `OrderConfirmed`, the **leader** acks, the relay calls `markPublished()` (the outbox row is now marked done, so it will **never be resent**). Then the leader broker is **replaced/dies** before a follower copied that record → the record is **lost**, and because the outbox already marked it published, there's no retry. payment-service never receives `OrderConfirmed`, so those orders sit **CONFIRMED, never paid**. `acks=all` + `min.insync.replicas=2` would have prevented it: the ack would only have come once **2 brokers** had the record, so a single broker loss couldn't lose it — and the relay's `markPublished()` would have been safe. "Faster" traded away the one guarantee the whole outbox→relay→at-least-once chain relies on.
</details>

---

## Tod ke dekho — Local (dev single-node; prod durability is config)

```bash
# dev Kafka is single-node (RF effectively 1). Inspect the real settings:
docker compose -f shopflow/docker-compose.yml exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic orders.events   # ReplicationFactor: 1, Partitions: 3 (dev)
# the producer already uses the durable settings (they just need a replicated cluster to matter):
grep -nE "acks|idempotence" shopflow/order-service/src/main/resources/application.yml   # acks: all, enable.idempotence: true
# prod MSK durability is Terraform — read/validate without AWS:
cd infra/terraform/aws && terraform validate
grep -nE "replication.factor|min.insync|num.partitions|auto.create|retention|number_of_broker" msk.tf
```

**Proved:** dev is RF1/3-partitions (single broker); the producer ships `acks=all`+idempotence; prod MSK sets RF3/minISR2/6-partitions/no-auto-create so `acks=all` actually means "2 of 3 replicas have it". Live durability needs the 3-broker MSK (`terraform apply`).

## Tod ke dekho — Online
AWS/MSK chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md). Dev: observe RF1 single-node + the DLT/lag labs in [comp 11](../11-kafka-dlt-lag/).

---

## Interview concepts is passage se
- **Managed MSK vs self-hosted / single-node dev** — durability by replication.
- **`acks=all` + RF3 + `min.insync.replicas=2`** — acked = on ≥2 brokers → survive 1 broker/AZ loss.
- **Idempotent producer** — retries don't duplicate.
- **min-ISR = durability vs availability** — stop writing rather than write a single copy.
- **`auto.create.topics.enable=false`** — no ghost topics from typos.
- **IAM auth (no passwords)** — pods via IRSA, ARN-scoped, SG from EKS only.
- **Outbox depends on a meaningful ack** — relay marks published only after a *durable* ack.

**Interviewer's lens:** "Kafka data loss kaise rokte?" → `acks=all` + RF3 + minISR2 (acked = 2 replicas); "min.insync.replicas kya karta?" → durability vs availability (2 brokers down → write fail, intended); aur `acks=1` ka silent-loss trap.

> Interview line: *"Dev single-node Kafka (RF1), prod MSK: 3 brokers / 3 AZs, `default.replication.factor=3`, `min.insync.replicas=2`, producer `acks=all` + idempotence — write tab ack hota jab 2 of 3 replicas ke paas ho, to ek broker/AZ gir jaye bina data loss. Relay `markPublished` sirf is durable ack ke baad karta. `auto.create.topics=false` (ghost topic nahi), 6 partitions, IAM auth (no password, pods IRSA se, ARN-scoped). 2 brokers down → minISR fail → acks=all write ruk jaata (intended: single copy pe likhne se behtar na likho). acks=1 kar do to leader-ack ke baad relay markPublished, leader marne pe event lost — orders CONFIRMED atke."*
</content>
