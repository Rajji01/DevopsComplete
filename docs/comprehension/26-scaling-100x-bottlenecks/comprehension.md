# 26 · Scaling ShopFlow 100x: where it breaks first — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service/OrderController` (`@RateLimiter limit-for-period: 50`), `*/application.yml` + `k8s/base/*/deployment.yaml` (`DB_POOL_SIZE: 5`), `inventory-service/ProductRepository.decrementStock` (atomic row-lock UPDATE), `shopflow-outbox/OutboxRelay` (`BATCH_SIZE=100`, `fixedDelay 1s`, SKIP LOCKED), `ProductController` (`@Cacheable`, no `sync`), MSK `num.partitions=6`.
> **Capstone:** ye topic poore correctness-model se judta → [25 — the correctness model](../25-correctness-model-capstone/). Yahan **correctness nahi, throughput/scale** ki baat hai.
> **Honesty note:** "Aaj ShopFlow X karta" = repo mein hai (file-cited). "100x pe fix Y" = architect ka next move, abhi **build nahi hua** — clearly marked. Interview mein yahi farak bolna.

---

## Scenario (read cold)

> *A staff-engineer design review of ShopFlow. "It works at today's load. Walk me through what breaks first as we go 100x, and why." Read once, then answer.*

ShopFlow today handles a modest, steady load comfortably. The question is **not** "is it correct" — topic 25 settled that: the atomic stock `UPDATE`, the order+outbox single transaction, and at-least-once + idempotency hold under any load. The question is **throughput**: as traffic climbs 100x, *which resource saturates first*, and is the fix a config knob, a new component, or a re-architecture?

Four ceilings are already visible in the code, each a different kind of limit:

**(1) The rate limiter is per-pod, not global.** `OrderController` is annotated `@RateLimiter(name="orders")` with `limit-for-period: 50` — **50 order-creations/sec per pod**. With 6 pods that's ~300/s cluster-wide, and the number **drifts as the HPA scales** (2–6 pods). So "our write cap" isn't a number you can state — it's `50 × current replicas`. A true global cap (protect the DB, honor a business SLA) needs a **shared limiter** the pods share, not a per-instance one.

**(2) The database connection pool is the real concurrency unit.** Each pod opens `DB_POOL_SIZE: 5` connections (k8s value; local default differs). Postgres/RDS has a hard `max_connections`. So **pods × 5 must stay under that ceiling** — scale order-service to 40 pods and you've asked for 200 connections from one service alone, before payment/inventory/notification take theirs. Horizontal pod scaling does **not** scale the database; past a point adding pods *starves* the DB instead of helping. The fix is a **connection pooler** (PgBouncer) in transaction mode, so thousands of pod-side connections multiplex onto a small server-side set — plus read replicas for the read load.

**(3) The hot row serializes the flash sale.** Overselling is prevented by `update product set quantity = quantity - :qty where sku=:sku and quantity >= :qty` — a **row lock** on that one SKU. For correctness that's perfect. For throughput, it means **every buyer of the same hot SKU is serialized on a single row** — they queue behind one lock. 300 buyers is fine; 300,000 buyers for one PS5 drop means the whole sale's throughput is bounded by how fast one row lock can be taken and released. More pods don't help — they all wait on the same row.

**(4) The outbox relay is a batch poller.** `OutboxRelay` runs `@Scheduled(fixedDelay 1s)`, pulls `BATCH_SIZE=100` unpublished rows with **SKIP LOCKED**, and sends them to Kafka **one at a time, waiting 5s for each ack**, stopping at the first failure to preserve order. SKIP LOCKED means **several pods can run the relay in parallel** without double-sending — that's the scale-out seam. But each relay instance is a serial, 100-per-batch, 1s-cadence loop; publish throughput per pod is bounded, and the 1s poll adds **up to ~1s of baseline event latency**. Beyond a point you shorten the delay, raise the batch, add relay pods — or move to **Debezium CDC** (tail the WAL, no polling), which the relay's own comment names as the alternative.

Two more lurk: **Kafka has `num.partitions=6`** (MSK), so consumer parallelism per topic caps at 6 — add partitions to go wider, but that **breaks per-key ordering during the rebalance** unless handled. And `ProductController`'s `@Cacheable` has **no `sync=true`**, so a hot product whose cache entry expires lets a **thundering herd** hit the DB at once (the D10/D12 stampede) — a single-flight cache is the fix.

The discipline: **name the first ceiling, prove it with the number, and say whether the fix is a knob, a component, or a redesign** — don't hand-wave "we'll scale horizontally," because three of these four ceilings *don't move* when you add pods.

---

## Questions

### A — Literal
1. Why can't you state ShopFlow's write throughput cap as a single number today?
2. What is the hard relationship between pod count, `DB_POOL_SIZE`, and the database that limits horizontal scaling?
3. What does `SKIP LOCKED` let you do with the outbox relay that a plain `SELECT ... FOR UPDATE` would not?

### B — Inference
4. For a single hot-SKU flash sale, why does adding more order-service pods **not** increase throughput — and what exactly are the pods contending on?
5. The relay waits 5s for each Kafka ack and stops the batch at the first failure. Why is "stop at first failure" there, and what throughput cost does it impose that a "skip the bad one and continue" design wouldn't?

### C — Trade-off
6. To raise event throughput you could (a) shorten the relay `fixedDelay`, (b) raise `BATCH_SIZE`, (c) add relay pods, or (d) switch to Debezium CDC. Rank these by effort-vs-payoff and name the downside of each.
7. Per-pod rate limiter vs a shared Redis/gateway limiter. The per-pod one is simpler and has no network hop — when is "drifts with replica count" actually *fine*, and when does it become a real problem?

### D — Transfer / extension
8. Marketing wants 1M users queued for a 100-unit drop. The atomic `UPDATE` is correct but serializes on one row. Design a path that keeps the oversell guarantee **and** absorbs the spike — and say where eventual consistency sneaks in.
9. You add PgBouncer in transaction-pooling mode so 40 pods share 20 server connections. What application-level feature can **break** under transaction pooling, and how do you check whether ShopFlow uses it?

### E — Spot-the-flaw
10. A dev "scales for the sale" by bumping the order-service HPA `maxReplicas` from 6 to 60 and declares it done. Load test passes at 10x; at 50x, **order-creation latency spikes and payment-service starts throwing connection errors**, even though order-service pods sit at 30% CPU. Explain the exact mechanism, and why "add more pods" made it *worse*, not better.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. Because the limiter is **per-pod** (`limit-for-period: 50`), the cluster cap is `50 × replicas`, and **replicas float** between the HPA's `min` and `max` (2–6) with load. There's no single shared counter, so the real cap is a moving number, not a stated SLA.
2. Each pod holds `DB_POOL_SIZE` (=5 in k8s) connections; the DB has a fixed `max_connections`. So **(pods across all services) × pool ≤ max_connections** is a hard ceiling. Adding pods consumes connections linearly; past the ceiling the DB refuses new connections — horizontal scaling of stateless pods does not scale the stateful DB.
3. `SKIP LOCKED` lets **multiple relay instances poll the same `outbox_event` table concurrently** — each grabs a different batch of unlocked rows and skips rows another pod has locked, so they don't collide or double-send. Plain `FOR UPDATE` would make the second poller **block** on the first's locked rows (serialized), killing the parallelism. SKIP LOCKED is what makes the relay horizontally scalable.
</details>

<details>
<summary>B — Inference</summary>

4. Because all pods issue the **same** `UPDATE ... WHERE sku=:sku` against the **same row**, and Postgres takes a **row lock** per statement. The buyers are serialized on that one lock regardless of how many pods issue the statement — the pods contend on the **database row**, not on CPU or pod count. Throughput is bounded by lock-acquire/release latency on that single row, so adding pods just adds more waiters to the same queue.
5. "Stop at first failure" preserves **ordering and at-least-once without gaps**: if event N can't publish (Kafka blip), the relay must not mark N+1..N+99 published and leave N behind — that would reorder and risk N never being retried as part of this ordered stream. So it stops, and the next 1s run retries from N. The cost: **one slow/failing event stalls the whole batch** (head-of-line blocking) — a "skip and continue" design would keep throughput up but would reorder and complicate the at-least-once bookkeeping. ShopFlow chose ordering/simplicity over max throughput here.
</details>

<details>
<summary>C — Trade-off</summary>

6. Rough ranking:
   - **(a) shorten `fixedDelay`** (e.g. 1s→200ms): trivial config, cuts baseline latency, but more empty polls = DB query load even when idle; diminishing returns.
   - **(b) raise `BATCH_SIZE`** (100→500): trivial, more per poll, but a bigger transaction holds the SKIP LOCKED rows longer and a single failure stalls a bigger batch.
   - **(c) add relay pods**: easy *because* SKIP LOCKED already makes it safe — near-linear publish throughput, bounded by DB and Kafka; the real horizontal lever.
   - **(d) Debezium CDC**: biggest payoff (no polling, tail the WAL, lowest latency, highest throughput) but biggest effort/operational cost — a new component (Kafka Connect + Debezium), WAL/replication-slot management, and a different failure model. Do it when polling genuinely can't keep up.
   Start with (a)+(b) knobs, then (c) pods, then (d) redesign only if needed.
7. Per-pod is **fine** when the limiter's job is **protecting a per-pod resource** — e.g. keeping one pod from exhausting its own 5 DB connections or its thread pool; each pod self-protects and the aggregate scales with the fleet, which is what you want. It becomes a **real problem** when the cap represents a **global contract** — a business SLA ("free tier: 100 orders/sec/customer"), or protecting a **shared** downstream (one RDS, a third-party payment API with its own rate limit). Then per-pod drift either over-admits (60 pods × 50 = 3000/s against a downstream rated for 500) or under-admits after scale-down. Global contract → shared limiter.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Keep the **atomic `UPDATE` as the source of truth** (never give up the oversell guarantee), but put a **queue/buffer in front** so the spike doesn't all hit the row at once:
   - Admit buyers into a **waiting room / FIFO queue** (e.g. a token/ticket per buyer); release them to the real `reserve` endpoint at a rate the hot row can sustain. 1M users, 100 units → the queue decides order; only a trickle reaches the `UPDATE`.
   - Optionally **pre-decrement in Redis** (atomic `DECR` on a counter seeded to 100) to reject the obvious losers instantly at the edge, then **reconcile** the real decrement against Postgres for the winners — Postgres stays authoritative, Redis is a fast admission gate. (This is the one place a cache touches stock, and only as a *gate*, never the final decision — topic 05's rule still holds: the `UPDATE` decides.)
   - **Eventual consistency sneaks in** at the Redis gate: Redis and Postgres counts diverge momentarily, so a few admitted buyers may still lose at the real `UPDATE` (handled as a normal 409). You trade a little "admitted-then-rejected" UX for absorbing the spike — and you accept that the queue position, not wall-clock arrival, defines fairness.
9. **Server-side state that outlives a single statement breaks** under transaction pooling — chiefly **session-scoped things**: prepared statements, `SET`/session variables, advisory locks held across statements, and **transaction-spanning features that assume the same backend connection**. PgBouncer transaction mode hands you a *different* backend connection per transaction, so anything relying on session continuity fails. Check ShopFlow: it's **Spring Data JPA/Hibernate with short `@Transactional` methods** and HikariCP — no long-lived session state, no cross-statement advisory locks (the stock lock is *within* one `UPDATE`), so transaction pooling is safe here. The thing to verify is Hibernate's **prepared-statement caching / `@Version`** behavior and that no code holds a connection across a remote call (topic 04's "no txn around remote call" rule already enforces that). If a feature needed session continuity, you'd use **session pooling** instead (less multiplexing).
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Bumping `maxReplicas` 6→60 scaled the **stateless** tier against a **stateful** bottleneck. At 60 order-service pods × `DB_POOL_SIZE 5` = **300 connections from order-service alone**, plus payment/inventory/notification pods taking theirs — together they blow past the DB's `max_connections`. New connections get **refused**, so payment-service (sharing the same Postgres instance / limit) **can't get a connection** → connection errors; order-service pods sit at **30% CPU** because they're **blocked waiting on the connection pool / the DB**, not doing work — CPU looks idle precisely because the bottleneck is downstream, not compute. "Add more pods" made it worse because each new pod **demands more scarce connections**, accelerating exhaustion. The correct scale move was **not** more pods: it was a **connection pooler (PgBouncer)** so many pods share few server connections, **read replicas** for the read load, and only *then* more pods. The HPA scales on CPU, which is the wrong signal when the real limit is DB connections — the classic "scaled the wrong tier" trap.
</details>

---

## Tod ke dekho — Local (dekho ki ceiling kahan hai)

Goal: config se khud dekho ki limits kya hain — fix deploy karne ki zaroorat nahi, **number verify** karo.

```bash
# (1) rate limiter is per-pod, value 50:
grep -nE "limit-for-period|RateLimiter" shopflow/order-service/src/main/resources/application.yml
#   -> limit-for-period: 50   (per pod; cluster cap = 50 x replicas)

# (2) DB pool per pod = 5 in k8s, and HPA max replicas:
grep -nA1 "DB_POOL_SIZE" shopflow/k8s/base/order-service/deployment.yaml      # value: "5"
grep -nE "maxReplicas|minReplicas" shopflow/k8s/base/order-service/hpa.yaml   # 2..6 -> at max, 6x5=30 conns just here

# (3) the hot row: one atomic UPDATE, one row lock:
grep -n "update Product" shopflow/inventory-service/src/main/java/com/shopflow/inventory/product/ProductRepository.java

# (4) relay cadence + batch + SKIP LOCKED (the scale-out seam):
grep -nE "fixedDelay|BATCH_SIZE|SKIP LOCKED|findUnpublished" \
  shopflow/shopflow-outbox/src/main/java/com/shopflow/outbox/OutboxRelay.java \
  shopflow/shopflow-outbox/src/main/java/com/shopflow/outbox/OutboxRepository.java

# (5) cache has NO sync=true -> stampede possible on a hot key:
grep -n "Cacheable" shopflow/inventory-service/src/main/java/com/shopflow/inventory/product/ProductController.java
```

**Proved:** teen ceilings (limiter, DB pool, hot row) pods add karne se **hilte nahi**; relay SKIP LOCKED se hi scale-out hota; cache mein `sync` missing = herd risk. Live load pe dekhna ho → topic 05 ka k6 flash-sale chalao aur DB connections/`pg_stat_activity` watch karo.

## Tod ke dekho — Online
Bigger load → [`ONLINE-LABS.md`](../ONLINE-LABS.md) (Codespaces/managed PG). `pg_stat_activity` se active connections, aur `pgbouncer` ka demo transaction-pooling ke liye.

---

## Interview concepts is passage se
- **"Which resource saturates first"** — scaling = find the first ceiling, not "add pods."
- **Stateless vs stateful tier** — pods scale, DB/connections/hot-row don't; wrong-tier scaling.
- **Per-pod vs global rate limit** — drift, shared limiter, when each is right.
- **Connection pooling (PgBouncer)** — pods × pool vs `max_connections`; transaction vs session mode.
- **Hot-row contention** — atomic correctness vs single-row throughput; queue / Redis-gate + reconcile.
- **Outbox relay scaling** — SKIP LOCKED multi-pod, batch/cadence knobs, CDC (Debezium) step-up, head-of-line blocking.
- **Partitions cap consumer parallelism** — 6 partitions, adding breaks per-key order.
- **Cache stampede / single-flight** — `@Cacheable sync=true`.

**Interviewer's lens:** kya tu **number se** bottleneck prove karta ("60 pods × 5 = 300 conn > max_connections") aur jaanta ki kaunsa fix **knob vs component vs redesign** hai — aur yeh ki "horizontally scale" teen mein se teen ceilings pe **kaam nahi karta**.

> Interview line: *"ShopFlow correct toh hai (atomic UPDATE, outbox+order ek txn, idempotency) — scale pe sawaal throughput ka. Pehla ceiling DB connections: har pod `DB_POOL_SIZE 5`, 60 pods = 300 conn, `max_connections` cross → payment bhi connection na mile, CPU 30% pe idle kyunki wait kar raha. Fix pods nahi — **PgBouncer** (transaction pooling) + read replicas. Rate limit per-pod 50 hai (cluster cap = 50×replicas, HPA se drift) → global SLA chahiye toh shared limiter. Hot-SKU flash sale ek row-lock pe serialize — pods add karne se nahi khulta; queue/waiting-room + Redis admission-gate, par final decide Postgres ka atomic UPDATE hi. Relay SKIP LOCKED se multi-pod scale karta, aage Debezium CDC. Partitions 6 → consumer parallelism cap. Matlab teen ceilings 'add pods' se hilte hi nahi — isliye pehle number se bottleneck naam do."*
