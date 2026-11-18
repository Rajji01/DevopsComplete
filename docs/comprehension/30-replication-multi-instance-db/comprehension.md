# 30 · Replication, DB-per-service & multi-instance writes: what breaks where — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in ShopFlow topology: **DB-per-service** (dev = 4 logical DBs `orders`/`payments`/`inventory`/`notifications` on one Postgres; prod = **RDS Multi-AZ + PITR**, [ARCHITECTURE.md]), relay **SKIP LOCKED** multi-pod coordination, outbox/saga cross-service, HLD §5 "read replicas / multi-region RDS" scale notes. Read-replica & multi-primary cases marked **[NEW]** — reasoned, not in the repo today.
> **Family:** [25 correctness](../25-correctness-model-capstone/) · [26 scaling](../26-scaling-100x-bottlenecks/) · [28 concurrency](../28-concurrency-control-locks-jpa-db/) · [29 silent-data](../29-silent-data-corruption-heisenbugs/). Ye topic un sabko **"data kahan rehta aur kaise replicate hota"** ke lens se jodta.

---

## Scenario (read cold)

> *A design review: "Walk the data topology — one DB or many, one instance or many, and what replication buys and costs at each layer." Read once, then answer.*

Where the data lives decides what can go wrong. ShopFlow makes three structural choices, and each creates its own class of problem.

**(1) DB-per-service (not one shared database).** Each service owns its **own** database — `orders`, `payments`, `inventory`, `notifications` are separate schemas (in dev, four logical DBs on one Postgres container; in prod, RDS). **No service reads another's tables.** This buys independent deploys, independent scaling, and a blast-radius boundary — but it **forbids a cross-service transaction and a cross-service JOIN**. order-service can't `UPDATE` payment's row in the same commit, and can't `JOIN orders` to `payments` in SQL. So "create order **and** publish its event atomically" becomes the **transactional outbox** (one local txn, then async relay), and "order ↔ payment consistency" becomes a **saga** with idempotency — eventual consistency by design, because a single ACID transaction **cannot** span two databases (and 2PC is avoided). This is topics 01/25 seen from the data side: the DB boundary is *why* those patterns exist.

**(2) Many instances, one database (per service).** order-service runs **many pods**, all connecting to the **same** `orders` DB. The database is the **shared truth**, so concurrency between pods is handled **at the DB**: the atomic stock `UPDATE` (row lock), `@Version` (optimistic), and the relay's `FOR UPDATE SKIP LOCKED` (so N pods drain the outbox without double-publishing) — topic 28. The cost is the **connection ceiling**: `pods × pool ≤ max_connections` (topic 26 / D17). Scaling the **stateless** pods doesn't scale the **stateful** DB; past the ceiling you need a pooler (PgBouncer) and replicas, not more pods.

**(3) Replication — copies of the data, with a consistency/latency dial.** A single DB instance is a single point of failure, so prod replicates. There are distinct **strategies**, each a different trade:
- **Synchronous standby (RDS Multi-AZ):** every commit is acknowledged only once a **standby** also has it. On primary failure, you fail over to the standby with **zero data loss** — but each write pays the round-trip, and if the standby is unreachable the write **waits**. This is for **HA/durability**, *not* for serving reads. (It's the exact shape of Kafka's `acks=all`+minISR from topic 23 — "acked = on ≥2 nodes," durability bought with latency/availability.)
- **Asynchronous read replicas:** the primary streams its WAL to replicas that **lag** by milliseconds-to-seconds; you route **read** traffic there to offload the primary. This **scales reads** cheaply but introduces **replication lag** → stale reads / read-your-writes misses (topic 29 bug 5). Async = you can lose the last few writes if the primary dies before they ship.
- **Logical replication / CDC (Debezium):** replicate **row changes per table** by tailing the WAL, feeding another system (a search index, a warehouse, or Kafka). This is the "step up" from the polling outbox relay in topic 26 — same data, pushed not polled.
- **[NEW] Multi-primary / active-active (multi-region):** two primaries both accept writes → **write-write conflicts** and **split-brain** if they diverge; you need conflict resolution (last-writer-wins, CRDTs) or to partition writes by key/region. ShopFlow's note is lighter: **multi-region RDS/MSK with idempotency keys already in place** (HLD §5) — idempotency makes cross-region replays safe, which is why it's "already there."

The thread: **the data boundary determines the failure mode.** One shared DB → cross-service coupling you don't want; DB-per-service → eventual consistency you must engineer (outbox/saga). One instance → SPOF; replicas → lag and failover semantics. Synchronous → safe but slower; asynchronous → fast but lossy/stale. There is no free copy of data — replication is a **dial between consistency, latency, and availability**, the same dial as CAP/PACELC (topic 25).

---

## Questions

### A — Literal
1. In ShopFlow's DB-per-service design, name the two things you **cannot** do that a single shared database would allow.
2. When many order-service pods share one `orders` DB, at which layer is concurrency resolved, and name the three mechanisms used.
3. Contrast synchronous standby vs asynchronous read replica in one line each: what each is **for**, and what each **costs**.

### B — Inference
4. "Create order and publish its event atomically" can't be one transaction across two systems. Explain **why the DB boundary forces** the outbox/saga, tying it to the CAP/PACELC framing of topic 25.
5. RDS Multi-AZ keeps a synchronous **standby**, yet you're told "don't send read traffic to it." Why would reading the standby be both **disallowed** and, even if allowed, **not** the read-scaling tool you want?

### C — Trade-off
6. Synchronous replication (zero data loss) vs asynchronous (lower latency, can lose the tail). For **payments** vs **a product-view counter**, which replication mode fits each, and why is matching mode-to-data the whole point?
7. DB-per-service gives independence but forbids cross-service JOINs. A reporting need wants "orders with their payment status" across both. Give two ways to serve it **without** a cross-service JOIN, and the trade of each.

### D — Transfer / extension
8. **[NEW]** You add async read replicas and route all `GET`s there. Checkout starts getting intermittent "I placed an order but my order list is empty" reports. Explain the exact mechanism, which reads must be re-routed, and two ways to implement "read-your-writes" correctly.
9. **[NEW]** Two app instances in **different regions** both accept a write to the **same** user's profile at nearly the same time (multi-primary). Describe the conflict, why "last-writer-wins by wall clock" is dangerous, and one safer resolution — and why ShopFlow's existing **idempotency keys** help with cross-region **replays** but **not** with this write-write conflict.

### E — Spot-the-flaw
10. To "reduce latency," a team points order-service and payment-service at the **same** shared database and has order-service directly `UPDATE` the `payment` table (skipping Kafka) "since it's all one DB now." It's faster in the demo. Name three distinct things this breaks — one about **coupling/deploys**, one about **blast radius**, one about **the saga/outbox** — and why the original DB-per-service boundary existed.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **(a)** A **cross-service transaction** — committing an `orders` change and a `payments` change atomically (a single ACID txn can't span two databases; 2PC is avoided). **(b)** A **cross-service JOIN** — querying `orders` joined to `payments` in SQL; each service's data is private, reachable only via its API/events.
2. At the **database** layer (the shared truth). Mechanisms: **atomic conditional `UPDATE`** (row lock) for stock, **`@Version`** optimistic locking for order state, and **`FOR UPDATE SKIP LOCKED`** for the relay so multiple pods divide the outbox. (Topic 28's three families.)
3. **Synchronous standby (Multi-AZ):** *for* HA/durability — zero-data-loss failover; *costs* write latency (wait for standby) and write availability if the standby is down. **Async read replica:** *for* read scaling/offload; *costs* replication **lag** (stale reads) and possible loss of the last writes on primary failure.
</details>

<details>
<summary>B — Inference</summary>

4. A single ACID transaction is a **single-database** construct; the order row and the event's delivery live in **different systems** (DB and Kafka) / the order and payment live in **different databases**. You physically **cannot** make them one atomic commit without 2PC (which couples them and blocks on failures — the availability hit CAP warns about). So you **choose availability** (each service commits locally and stays up) and **recover consistency asynchronously**: the **outbox** makes "order + intent-to-publish" one *local* txn, the **relay** delivers at-least-once, and **idempotency + saga** converge the rest. That's PACELC's "else, latency vs consistency" resolved toward **latency/availability with eventual consistency** — forced by the data boundary, not a preference.
5. **Disallowed:** the synchronous standby in RDS Multi-AZ is a **failover target**, not a readable endpoint — AWS doesn't let you read it; its job is to be an exact, caught-up copy ready to promote. **Not the right tool anyway:** even if readable, a *synchronous* replica doesn't **scale reads** without also slowing **writes** (every write waits for it), and you only have **one** standby — read scaling wants **many async replicas** whose lag doesn't gate writes. HA and read-scaling are **different problems** solved by **different replicas** (sync standby vs async read-replicas).
</details>

<details>
<summary>C — Trade-off</summary>

6. **Payments → synchronous** (zero data loss): an acknowledged payment that vanishes on failover is unacceptable — you'd rather pay write latency / briefly block than lose money state. **Product-view counter → asynchronous** (and honestly, cache/approximate): losing the last few increments of a view count on failover is harmless, and you want it cheap and fast. **Matching mode to data is the point** because replication's cost (latency/availability for sync; staleness/loss for async) is only worth paying where the data's value demands it — one global setting either over-pays for counters or under-protects money. (Same lesson as Kafka minISR per topic 23: durability where it matters.)
7. **(a) Read model / materialized view:** payment-service publishes payment-status events; a **read-optimized projection** (owned by whoever serves the report, or a reporting DB/warehouse fed by CDC) joins them **outside** the transactional path — eventually consistent, but no cross-service coupling. **(b) API composition:** the reporting service calls order-service and payment-service APIs and **joins in memory** — simple, always fresh, but N calls and no SQL-level joins/aggregation at scale. Trade: materialized view = fast/scalable but eventually-consistent and more infra; API composition = fresh and simple but chatty and weak for big aggregations.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **[NEW]** Mechanism: `POST /orders` writes to the **primary** (`200`), but the immediate `GET /orders` is routed to an **async replica** that **hasn't received the new row yet** (replication lag) → the list is empty/stale. It's **intermittent** (depends on lag at that instant) and invisible as an error. **Must re-route:** reads that a user issues **right after their own write** (their order list just after placing, their profile just after editing) — read-your-writes. **Two implementations:** **(i) sticky/primary-pin:** for N seconds after a user's write (or if a "write cookie"/LSN token is present), route **that user's** reads to the **primary**; **(ii) LSN wait:** capture the write's log position and have the read **wait until the replica has replayed ≥ that LSN** (bounded staleness) before serving. Cross-user/aggregate reads stay on replicas.
9. **[NEW]** Conflict: both regions accept a write to the same profile row concurrently; when they replicate to each other, they **disagree** — a **write-write conflict** (and if the link drops, **split-brain**: each region thinks it's authoritative). **Last-writer-wins by wall clock is dangerous** because clocks across regions **skew**, so "last" is decided by an unreliable clock — a slightly-fast node's **older** edit can silently **beat** a newer one, losing the real latest change with no error. **Safer:** partition writes so a given user is **owned by one region** (route their writes home), or use **version vectors / CRDTs** to merge deterministically, or app-level merge with a monotonic version. **Idempotency keys help replays, not this:** an idempotency key makes a **re-delivered identical** operation a no-op (safe cross-region **replay**), but two **different** concurrent edits have different intent — idempotency can't decide **which** should win; that needs conflict resolution, a separate concern.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Three breaks: **(coupling/deploys)** order-service now writes payment's schema directly, so the two services are **coupled at the database** — payment-service can't change its table, migrate, or deploy independently without breaking order-service; you've lost the independent-deploy benefit that DB-per-service existed for. **(blast radius)** one shared database means one failure domain — a lock storm, a bad migration, or connection exhaustion on that DB takes down **both** services at once (and the connection-ceiling math now mixes both services' pods); the boundary that contained blast radius is gone. **(saga/outbox)** writing payment directly **skips the event/outbox**, so there's **no at-least-once event, no idempotent consumer, no saga/reconciler** — the moment that direct write and the order write aren't in the same local txn (they can't be, if truly separate, and shouldn't be even if co-located), you've recreated the **dual-write** problem the outbox was built to solve, with no recovery path. The DB-per-service boundary existed precisely to force **async, idempotent, independently-deployable** integration — "it's all one DB now" trades correctness and independence for a demo-only latency win.
</details>

---

## Tod ke dekho — Local (topology khud dekho)

```bash
# (1) DB-per-service: four separate databases, one instance (dev):
docker compose -f shopflow/docker-compose.yml exec postgres psql -U postgres -c "\l"   # orders, payments, inventory, notifications
grep -n "DB_URL" shopflow/docker-compose.yml   # each service -> its OWN db name on postgres:5432

# no service reaches another's tables — order-service user can't see payments:
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U orders -d orders -c "\dt"     # orders/outbox_event/processed_event only — no payment table

# (2) many instances, one db: scale a service and watch all pods share the same DB (k8s):
# kubectl scale deploy/order-service --replicas=4   # all 4 -> same orders DB; relay SKIP LOCKED divides work

# (3) replication is prod (RDS Multi-AZ) — dev is single-node; the intent is in the docs:
grep -n "Multi-AZ\|read replica\|RDS" docs/ARCHITECTURE.md docs/HLD-SYSTEM-DESIGN.md
```

**Proved:** four private DBs (DB-per-service), each service sees only its own tables (no cross-service JOIN), all pods of a service share that one DB (concurrency at the DB, relay via SKIP LOCKED). Replication/replicas are prod/RDS — dev is single-node, so lag/failover is reasoned, not shown locally.

## Tod ke dekho — Online
Managed PG with a **read replica** (Neon/RDS) → [`ONLINE-LABS.md`](../ONLINE-LABS.md): write to primary, read from replica, measure the lag window (the only way to *see* read-your-writes staleness).

---

## Interview concepts is passage se
- **DB-per-service** — independence + blast-radius, but **no cross-service txn/JOIN** → outbox/saga, eventual consistency.
- **Many instances, one DB** — concurrency at DB (locks, SKIP LOCKED), connection ceiling (pods×pool).
- **Replication strategies** — synchronous standby (HA, zero-loss, write-latency) vs async read-replica (read-scale, lag) vs logical/CDC (Debezium) vs multi-primary (conflicts/split-brain).
- **Lag → read-your-writes** — route fresh reads to primary / LSN-wait.
- **Match replication mode to data value** — payments sync, counters async (topic 23 minISR analog).
- **Serve cross-service reads** — materialized view (CDC) or API composition, never a cross-DB JOIN.
- **Failover semantics** — sync promote = no loss; async = tail loss + split-brain risk; idempotency ≠ conflict resolution.

**Interviewer's lens:** kya tu **data boundary** se failure-mode derive kar sakta (DB-per-service → eventual consistency, one-instance → SPOF, async → lag), replication ki **teen strategy ko kaam se match** kar sakta, aur read-your-writes / multi-primary conflict ko naam de sakta — aur jaanta ki idempotency replay ke liye hai, write-write conflict ke liye nahi.

> Interview line: *"Data boundary hi failure-mode decide karta. Mere project mein **DB-per-service** — orders/payments/inventory/notifications alag DB; isliye cross-service txn/JOIN nahi ho sakta, to **outbox + saga** (eventual consistency, 2PC avoid). Ek service ke **bahut pods, ek DB** — concurrency DB pe (atomic UPDATE / `@Version` / relay `SKIP LOCKED`), aur ceiling `pods×pool ≤ max_connections`. Replication prod mein: **RDS Multi-AZ synchronous standby** (HA, zero-loss failover, write-latency — Kafka minISR jaisा) read ke liye nahi; **async read-replica** read-scale ke liye par **lag → read-your-writes stale** (fresh read primary pe / LSN-wait); **CDC/Debezium** logical; **multi-primary** = write-conflict/split-brain, aur idempotency sirf **replay** safe karta, conflict resolve nahi. Payments sync, view-counter async — mode ko data se match karo."*
