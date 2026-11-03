# 26 · Scaling 100x — NOTES (kya / easy / kyu / kaise + diagrams)

> Pair: [`comprehension.md`](comprehension.md). Capstone correctness: [25](../25-correctness-model-capstone/). Yeh topic **throughput** ka hai, correctness ka nahi.

---

## KYA (one breath)
ShopFlow **correct** hai har load pe. Par 100x traffic pe **koi ek resource pehle saturate** hoga. Architect ka kaam: **kaunsa pehle**, number se prove, aur fix **knob / component / redesign** — teen mein kya. Aur yaad rakho: **4 mein se 3 ceilings "pods add karo" se hilte hi nahi.**

## EASY WAY (ek line se yaad)
> **"Pods stateless scale karte, DB/limiter/hot-row stateful — unhe pod add karne se farak nahi padta."**
Scaling = **pehla ceiling dhoondo**, horizontally-scale ka mantra andha hai.

## 4 CEILINGS (ground truth — repo mein hai)
| # | Ceiling | Number (repo) | Pods add karne se? | Fix type |
|---|---------|---------------|--------------------|----------|
| 1 | Rate limiter **per-pod** | `limit-for-period: 50` /pod | cap badhta par **drift** (50×replicas) | global limiter = **component** |
| 2 | **DB connections** | `DB_POOL_SIZE: 5`/pod vs `max_connections` | **ULTA kharab** (conn khatam) | PgBouncer + replicas = **component** |
| 3 | **Hot row** (ek SKU) | 1 row lock /UPDATE | **kuch nahi** (sab ek lock pe wait) | queue + Redis-gate = **redesign** |
| 4 | **Outbox relay** | `BATCH_SIZE=100`, `fixedDelay 1s` | **scale karta** (SKIP LOCKED) | knob→pods→CDC |
| + | Kafka parallelism | `num.partitions=6` | consumer cap=6 | add partitions (order caveat) |
| + | Cache stampede | `@Cacheable` **no `sync`** | herd on expiry | `sync=true` / single-flight |

**Bolo ise:** sirf #4 (aur partitions) horizontal scaling se khulte; #1/#2/#3 nahi.

## KYU (har ceiling ka asli reason)
- **#1 per-pod:** Resilience4j limiter har instance ka apna counter — shared nahi. Toh cluster cap = `50 × replicas`, aur HPA (2–6) se **number float** karta. Business SLA/shared downstream ke liye ye bekaar; per-pod **self-protection** ke liye theek.
- **#2 DB pool:** har pod `5` conn khol-ta; DB ka `max_connections` fixed. `(sab services ke pods) × pool ≤ max_connections` = **hard wall**. Pod add = conn add (linear) → wall cross → DB **refuse** → doosri service ko bhi conn na mile. **Stateless tier scale karne se stateful DB scale nahi hota.**
- **#3 hot row:** `update product set quantity=quantity-:qty where sku=:sku and quantity>=:qty` → Postgres **row lock**. Correct (kabhi oversell nahi), par ek SKU ke saare buyers **ek lock pe serialize**. Pod count irrelevant — sab usi row ka wait karte.
- **#4 relay:** `@Scheduled(1s)` → `findUnpublished(Page 0,100)` **SKIP LOCKED** → ek-ek Kafka send, 5s ack wait, **first failure pe ruk** (ordering). SKIP LOCKED = **multi-pod safe** → yahi scale-out seam. Par per-pod serial loop + 1s poll = baseline latency.

## KAISE (fix path — honest: abhi build NAHI hua, ye architect move hai)
```
Load badhti gayi →
 step 0 (knob)     : relay fixedDelay 1s->200ms, BATCH 100->500   [config]
 step 1 (pods)     : +relay pods (SKIP LOCKED already safe)       [scale-out seam]
 step 2 (component): PgBouncer (txn pooling) + read replicas       [DB ceiling fix]
 step 3 (component): shared Redis/gateway rate limiter             [global cap]
 step 4 (redesign) : flash-sale waiting-room + Redis admission-gate[hot row]
 step 5 (redesign) : Debezium CDC (no polling, tail WAL)           [relay ceiling]
 step 6 (knob+care): +Kafka partitions (handle per-key reorder)    [consumer cap]
```

## DIAGRAM 1 — "pods add karo" kahan kaam karta, kahan nahi
```
                 add order-service pods  ──►  +CPU +HTTP capacity   (GOOD)
                           │
                           ▼
   ┌────────── but they all funnel into shared, stateful things ──────────┐
   │                                                                       │
   ▼                         ▼                          ▼                  ▼
[DB max_connections]   [one hot-SKU row]        [per-pod limiter]    [Kafka 6 parts]
 pods×5 > wall          all serialize on         cap = 50×pods        consumers ≤ 6
 => WORSE               one row lock             => DRIFTS            => capped
 (conn refused)         => NO CHANGE             (not global)
```
**Teen jagah pod add karna ya ulta (DB), ya bekaar (hot row), ya sirf drift (limiter).**

## DIAGRAM 2 — flash-sale hot row ka fix (correctness bachate hue)
```
1M buyers ──► [Waiting room / FIFO queue] ──trickle──► [Redis DECR gate (seed=100)]
                                                              │ winner?
                                                       no ◄───┤───► yes
                                                     (instant 409)   │
                                                                     ▼
                                              [Postgres atomic UPDATE]  ← SOURCE OF TRUTH
                                                (final decide; row lock)
                                                                     │
                                              reconcile: Redis vs PG (few admitted may still 409)
```
- Redis sirf **admission gate** (fast reject), **final decision Postgres** ka — topic 05 rule intact.
- **Eventual consistency** yahin ghusti: Redis↔PG count thoda diverge; kuch "admitted-then-409" — normal business outcome.

## DIAGRAM 3 — relay scale-out (SKIP LOCKED magic)
```
 outbox_event table (unpublished rows)
    │            │            │
 [relay pod A] [relay pod B] [relay pod C]   each: SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100
    │ grabs      │ grabs       │ grabs        -> different rows, no collision, no double-send
    ▼            ▼             ▼
  Kafka        Kafka         Kafka            (plain FOR UPDATE: B/C would BLOCK on A -> no scale)
```

## ALTERNATE (kis fix ki jagah kya, kab)
| Problem | ShopFlow aaj | Alternate | Kab alternate |
|---------|--------------|-----------|---------------|
| write cap | per-pod limiter 50 | Redis token-bucket / API-gateway limit | global SLA / shared downstream |
| DB conns | HikariCP 5/pod direct | **PgBouncer** txn-pool + read replicas | pods × pool → max_connections paas |
| hot SKU | atomic UPDATE (serialize) | queue + Redis gate + reconcile | single-SKU mega flash sale |
| event publish | poll relay (1s/100) | **Debezium CDC** (WAL tail) | polling latency/throughput tang |
| consumer parallel | 6 partitions | more partitions / keyed re-partition | lag consistently > SLA |
| cache herd | `@Cacheable` no sync | `sync=true` / single-flight / jittered TTL | hot key, expiry stampede (D10/D12) |

## UNDERSTANDING CHECK (khud bata)
1. Order-service CPU 30%, phir bhi latency spike + payment conn-errors — ek line mein kyun? → *pods × 5 conn > `max_connections`; pods DB wait pe idle, CPU jhoota signal.*
2. 6 → 60 pods flash-sale ke liye — hot SKU throughput kitna badha? → *bilkul nahi; sab ek row lock pe serialize.*
3. SKIP LOCKED hata ke plain FOR UPDATE kar do — relay ka kya? → *doosra pod block, scale-out khatam (serialize).*
4. Per-pod limiter kab theek, kab bura? → *theek: per-pod resource self-protect; bura: global SLA / shared downstream (drift se over/under-admit).*
5. Flash-sale redesign mein eventual consistency kahan? → *Redis gate ↔ Postgres count divergence; kuch admitted-phir-409.*

## INTERVIEW LINE (ek saans)
> *"Correctness settled; scale pe sawaal 'pehla ceiling kaun'. Number do: 60 pods × `DB_POOL_SIZE 5` = 300 conn > `max_connections` → payment ko bhi conn na mile, order-service CPU 30% pe idle (DB wait). Fix pods nahi — PgBouncer txn-pooling + read replicas. Rate limit per-pod 50 (cap=50×replicas, HPA drift) → global SLA ho toh shared Redis limiter. Hot-SKU sale ek row-lock pe serialize, pods se nahi khulta → waiting-room + Redis admission-gate, par final Postgres atomic UPDATE. Relay SKIP LOCKED se multi-pod scale, phir Debezium CDC. Partitions 6 = consumer cap. Point: 4 mein 3 ceilings horizontal scaling se hilte hi nahi — isliye andha 'scale out' mat bolo, number se bottleneck naam do."*

---
**Grounded:** `application.yml` (limiter 50), `k8s/base/order-service/{deployment,hpa}.yaml` (pool 5, 2–6), `ProductRepository.decrementStock` (row lock), `shopflow-outbox/OutboxRelay` (100/1s/SKIP LOCKED), `ProductController` (`@Cacheable` no sync), MSK `num.partitions=6`. Fix-paths = architect proposal (abhi repo mein nahi) — interview mein wahi honesty bolna.
