# 23 · MSK prod Kafka (durability, IAM auth) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `infra/terraform/aws/msk.tf`, `order-service/application.yml` (`acks: all`), `irsa.tf` (kafka-clients). Pairs with [comp 01](../01-outbox-dual-write/), [comp 11](../11-kafka-dlt-lag/).

## Kya (one line)
Prod mein single-node dev Kafka ki jagah **MSK**: 3 brokers / 3 AZs, `replication.factor=3` + `min.insync.replicas=2` + producer `acks=all` → write tab ack jab **2 of 3 replicas** ke paas ho, to ek broker/AZ loss pe **koi acked event nahi khota**; IAM auth (no passwords, pods IRSA se).

## Easy way (Hinglish analogy)
Important document ki **3 photocopies** 3 alag lockers (AZs) mein. **acks=all + minISR2** = "jab tak kam se kam **2 copies** safe na ho, 'done' mat bolo". Ek locker (broker/AZ) jal jaye → 2 copies bachi, document safe. Agar sirf 1 copy pe 'done' bol diya (`acks=1`) aur wahi locker jala → document gaya. Aur locker kholne ke liye **chaabi (password) nahi** — tumhari building-ID (IRSA role) se hi khul jaata.

## Kyu (ye problem solve karta hai)
- Single broker = zero redundancy; marr gaya to in-flight events gaye. Prod mein ye acceptable nahi (orders/payments).
- **Outbox relay `markPublished` sirf broker ack ke baad karta** — to ack ka *matlab durable hona chahiye*. minISR2 ack ko "2 replicas ke paas" bana deta; minISR1 hota to leader-only ack → leader marne pe loss.
- `auto.create.topics=false` typo se ghost-topic nahi banne deta (events chup-chaap kho jaate).
- IAM auth: SASL password nahi; pods IRSA se auth, ARN-scoped, SG sirf EKS nodes se.

## Kaise (step by step)
1. **MSK cluster** (`msk.tf`): kafka 3.6.0, 3 broker nodes, private subnets, EBS 100GB.
2. **Config**: `auto.create.topics.enable=false`, `default.replication.factor=3`, `min.insync.replicas=2`, `num.partitions=6`, `log.retention.hours=168`.
3. **Producer** (`application.yml`): `acks: all`, `enable.idempotence: true` → 2/3 replicas + no dup on retry.
4. **Auth**: IAM; kafka-clients IRSA role → `kafka-cluster:Connect`, read/write `orders.events*`/`payments.events*`, group ARNs (comp 20).
5. **Network**: SG ingress 9092–9098 sirf EKS node SGs se, private subnets.
6. **Dev→prod**: same topics + client code; sirf broker count / RF / auth / partitions badalte (config/infra).

## Flow diagram (acks=all durability)
```
 producer (acks=all, idempotent) ──► leader broker (AZ-a)
                                       │ replicate
                                       ├──► follower (AZ-b)   ◄─ in-sync
                                       └──► follower (AZ-c)   ◄─ in-sync
   ACK tab jab >= min.insync.replicas(2) ke paas ho  ──► producer ko success
   relay markPublished() ── sirf is durable ack ke baad ──► outbox row done
 AZ-a down → leader fail-over (AZ-b), 2 replicas abhi bhi → write/read chalu, 0 loss
 2 brokers down → in-sync = 1 < minISR 2 → acks=all WRITE FAIL (intended: durable na ho to likho mat)
```

## Dev vs prod diagram
```
 dev (compose KRaft)          prod (MSK)
 ─────────────────────────────────────────────
 1 broker                     3 brokers / 3 AZs
 RF 1, partitions 3           RF 3, minISR 2, partitions 6
 PLAINTEXT                    TLS + IAM auth (IRSA, no password)
 auto-create on               auto.create.topics=false
 (producer acks=all already — needs a replicated cluster to matter)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Single-node (dev)** | 1 broker KRaft | simple local | zero redundancy | dev |
| **MSK RF3/minISR2** *(prod)* | managed, 3 AZ | no ops, durable | AWS cost/lock-in | prod |
| **Self-managed on EC2** | run brokers | control, cheaper | you operate/patch | custom/scale |
| **acks=1** | leader-only ack | lower latency | **silent loss** on leader death | kabhi nahi (events) |
| **minISR=1** | 1 replica ok | more available writes | acked != durable | low-value data |

## Understanding check
1. Dev vs prod durability farak?
2. `acks=all`+minISR2+RF3 milke kya guarantee?
3. Relay ke ack ko minISR2 "zyada meaningful" kaise banata?
4. 2 brokers down → acks=all write ka kya, intended kyu?
5. `acks=1` ka silent-loss sequence?

## Interview mein ye bolo
*"Dev single-node Kafka (RF1), prod MSK: 3 brokers/3 AZs, replication.factor=3, min.insync.replicas=2, producer acks=all + idempotence — write tab ack jab 2 of 3 replicas ke paas ho, to ek broker/AZ loss pe koi acked event nahi khota. Outbox relay markPublished sirf is durable ack ke baad karta. auto.create.topics=false (ghost topic nahi), 6 partitions, IAM auth (no password, pods IRSA se ARN-scoped). 2 brokers down → minISR fail → acks=all write ruk jaata (intended: single copy pe likhne se behtar na likho). acks=1 kar do to leader-ack pe markPublished, leader marne pe event lost — orders CONFIRMED atke."*

> Related: [`../01-outbox-dual-write/`](../01-outbox-dual-write/) (relay ack), [`../11-kafka-dlt-lag/`](../11-kafka-dlt-lag/) (DLT/lag/ordering), [`../20-eks-irsa-least-privilege/`](../20-eks-irsa-least-privilege/) (kafka-clients IRSA).
</content>
