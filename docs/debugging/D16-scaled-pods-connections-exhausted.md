# D16 · You scaled pods to fix load — now payment-service is throwing connection errors

> Skill: capacity / "scaled the wrong tier" debugging. Related mechanism: [`../comprehension/26-scaling-100x-bottlenecks/`](../comprehension/26-scaling-100x-bottlenecks/) (ceilings that don't move with pods), [`../comprehension/04-order-limbo-reconciler/`](../comprehension/04-order-limbo-reconciler/) (pool pressure). Sibling of [D03](D03-latency-spike-no-errors.md) (that one is *organic surge → one pool slow*; this one is *operator action → shared DB ceiling → refused across services*).

---

## Page (symptom)

Flash sale incoming. To "get ahead of load," an on-call engineer bumped order-service's HPA `maxReplicas` from **6 → 60** and the pods scaled up. Load test at 10x passed earlier, so this looked safe.

At ~50x real traffic, two things happen at once:
- **order-service** `POST /orders` p99 spikes and some requests now **500**, not just slow.
- **payment-service** — which nobody touched — starts logging `FATAL: sorry, too many clients already` / `Connection is not available` and failing to process `OrderConfirmed`. Orders pile up in **CONFIRMED, not moving to PAID**.

And the confusing part: **order-service pods sit at ~30% CPU.** The HPA won't scale further (CPU target not hit), the pods look "healthy," yet latency and errors are climbing.

**Before details: a service you didn't deploy or scale (payment) is failing, and your "healthy" pods are idle on CPU. What single shared resource explains all three facts at once?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- order-service: **60/60 pods Running, Ready**; CPU **~30%**; p99 up, error rate up (~some 500s).
- order-service logs: `HikariPool-1 - Connection is not available, request timed out after 30000ms`.
- payment-service (untouched, still 3 pods): logs `FATAL: sorry, too many clients already` from the JDBC driver; consumer lag on `payments` building.
- Postgres: `SELECT count(*) FROM pg_stat_activity;` ≈ **at `max_connections`**; `max_connections` is instance-wide and shared by the orders/payments/inventory databases on this instance.
- Each pod's `DB_POOL_SIZE = 5` (k8s deployment env). 60 order pods × 5 = **300 connections demanded by order-service alone**, before payment/inventory/notification take theirs.
- No code deploy; the only change in the timeline is **HPA maxReplicas 6 → 60** and the pod count climbing.
- DB CPU moderate, disk fine — the DB box isn't out of CPU, it's out of **connection slots**.
</details>

---

## Your move

1. Three facts — order-service 500s, payment-service (untouched) failing, pods at 30% CPU — point at **one** shared resource. Name it and explain how it produces all three.
2. Why does **more pods make this worse, not better**? Walk the arithmetic.
3. Why is CPU **30%** (and therefore the HPA quiet) even though the service is clearly in trouble? What does "30% CPU" actually mean here?
4. Why did the **10x load test pass** but 50x (via raw pod-scaling) fail — what did the test not exercise?
5. Give the **wrong fix** (the tempting one) and the **right fix**, and say which is a knob, which is a component.

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The one shared resource (Q1): Postgres `max_connections`.** It's instance-wide and shared by every database (orders, payments, inventory) on that instance. order-service scaled to 60 pods × `DB_POOL_SIZE 5` = **300 connections demanded**, which (with the other services' pods) exhausts the instance's connection slots. Now:
- order-service pods can't get all the connections their pools want → Hikari `Connection is not available ... timed out` → requests block then **500**.
- payment-service, sharing the **same instance limit**, is **refused** new connections (`too many clients already`) even though *it* didn't change — it's collateral damage of order-service eating the shared budget → it can't consume `OrderConfirmed` → orders stuck CONFIRMED (the D01 symptom, but from a different cause).
- The pods are "healthy" by liveness/readiness and idle on CPU because they're **blocked waiting on the pool**, not computing.

**More pods = worse (Q2).** Each new order-service pod opens its **own** pool and demands **5 more** connections from a **fixed** instance budget. Scaling the stateless tier *increases* pressure on the stateful ceiling. Past the point where `(all pods across all services) × pool = max_connections`, every added pod **steals** connections from the others — you're not adding capacity, you're redistributing (and exhausting) a fixed one. This is topic 26's rule live: **the DB-connection ceiling does not move when you add pods — it gets worse.**

**30% CPU explained (Q3).** The threads are **parked waiting for a database connection** (pool acquire), not running code. Blocked-on-IO/lock time doesn't burn CPU, so CPU reads low precisely **because** the bottleneck is elsewhere. This is the classic trap: **CPU is a useless health signal when the limit is a connection/lock**, and the HPA — which scales on CPU — stays quiet (or worse, had it been able to scale, it would have added pods and deepened the exhaustion). Right signal here: Hikari `pending`/acquire-timeout and `pg_stat_activity` count, not CPU.

**Why 10x passed, 50x failed (Q4).** The 10x test likely ran at a **modest pod count** (within the 6-replica world) where total connections stayed under `max_connections`. It validated throughput at a scale that **never crossed the connection ceiling**, so the ceiling was invisible. The failure mode only appears once `pods × pool` exceeds the instance budget — a threshold the test didn't reach. Lesson: a load test must **scale the same way production will** (same replica ceiling, same shared DB) or it certifies a configuration you won't run.

**Root cause:** scaling the **stateless** tier (order-service pods 6→60) drove `pods × DB_POOL_SIZE` past the **shared Postgres `max_connections`**, exhausting the instance-wide connection budget and starving every service on it — an error/saturation problem caused by a capacity decision, not a code bug.

**Wrong vs right fix (Q5):**
- **Wrong (tempting):** scale order-service *even more* (it's under load, right?), or just raise each pod's `DB_POOL_SIZE`. Both **demand more connections from the exhausted budget** → deepen the outage. (Raising `max_connections` on Postgres also mostly backfires — each connection costs memory/backend process; you hit a different wall.)
- **Right, immediate:** **scale order-service back down** to a pod count where `pods × pool < max_connections`, which instantly returns connections to payment-service and clears the stuck orders.
- **Right, durable (component, not knob):** put **PgBouncer** (transaction-pooling mode) in front of Postgres so hundreds of pod-side connections **multiplex onto a small server-side set** — now pod count and DB connections are decoupled. Add **read replicas** for the read path. *Then* pods can scale freely. (ShopFlow's JPA code uses short `@Transactional` methods with no cross-statement session state, so transaction pooling is safe — see comp 26 Q9.)
- **Right, prevention:** HPA should not scale a connection-bound service on **CPU** alone; and capacity-plan `max(replicas) × DB_POOL_SIZE` against `max_connections` **per instance**, counting *all* services sharing it.

**Confirm the fix:** `pg_stat_activity` count drops below `max_connections`; payment-service stops logging `too many clients`, consumes the `payments` backlog, orders move CONFIRMED→PAID; order-service Hikari acquire-timeouts stop, p99 recovers.
</details>

---

## Reproduce / test (apne haath se)
```bash
# shrink the DB connection ceiling to make the exhaustion reproducible on a laptop:
#   docker-compose.yml postgres service ALREADY has:  command: ["postgres","-c","max_connections=200"]
#   change that 200 -> 20 (so a few pods x pool exhaust it), then `make up`
make up && TOKEN=$(make -s token)

# watch the shared ceiling as you add demand:
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U postgres -c "show max_connections;"
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U postgres -c "select datname, count(*) from pg_stat_activity group by datname order by 2 desc;"

# raise order-service pool / replicas toward the ceiling and re-check: pending acquires + 'too many clients' appear.
curl -s localhost:8081/actuator/prometheus | grep -E 'hikaricp_connections_(active|pending|acquire)'   # pending/acquire climbing = starved
# the tell: order-service CPU stays low while requests time out -> blocked on the pool, not CPU-bound.
```
**Proved:** with a small `max_connections`, pushing `pods × DB_POOL_SIZE` past it reproduces the cross-service starvation — one service's pods refuse connections to another, CPU idle. (On k8s: scale the Deployment replicas instead of docker.)

## The pattern

- **Scaling the stateless tier into a stateful ceiling makes it worse.** Pods are cheap; the shared DB connection budget is fixed. `pods × pool` is the real number — plan it against `max_connections` across **all** services on the instance.
- **A service you didn't touch failing = look for a shared resource** (DB connections, a DB instance, a Kafka cluster, a rate-limited third party), not that service's own code.
- **CPU is a liar when the bottleneck is a connection or lock.** Blocked-on-pool threads read as *low* CPU; the HPA (CPU-based) stays quiet or scales the wrong way. Watch pool acquire-timeouts and `pg_stat_activity`.
- **"It passed at 10x" means nothing if the test didn't cross the ceiling.** Load-test at the **real** replica ceiling against the **real** shared DB.
- **The fix for a connection ceiling is a pooler (PgBouncer) + replicas — a component — not a bigger number.** Decouple pod count from DB connections, *then* scale pods.

> Grounded in: `k8s/base/*/deployment.yaml` (`DB_POOL_SIZE: 5`), `k8s/base/order-service/hpa.yaml` (replica range), `*/application.yml` HikariCP, shared Postgres `max_connections` (`HLD-SYSTEM-DESIGN.md` capacity math: pool 5/pod). Mechanism + fix-path: [`../comprehension/26-scaling-100x-bottlenecks/`](../comprehension/26-scaling-100x-bottlenecks/). (Fix path = architect proposal; not yet in the repo.)
