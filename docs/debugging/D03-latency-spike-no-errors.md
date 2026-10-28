# D03 · Checkout p99 spikes to 4s, but 5xx stays normal

> Skill: latency / resource-saturation debugging. Related mechanism: [`../comprehension/09-resilience-breaker-retry/`](../comprehension/09-resilience-breaker-retry/), [`../comprehension/13-tracing-logs-correlation/`](../comprehension/13-tracing-logs-correlation/).

---

## Page (symptom)

`POST /api/v1/orders` p99 latency jumped from ~250ms to **~4s** and is riding there. The **error rate is normal** (5xx < 0.5%) — requests are *succeeding*, just slowly. The checkout **SLO burn-rate alert** is now firing (slow requests count against the SLO).

Users aren't seeing errors; they're seeing a spinner. Conversion is dropping.

**Before details: slow-but-succeeding is a different animal than erroring. Where do you look, and what's your first hypothesis for "slow, not broken"?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- order-service RED: Rate normal, **Errors normal**, **Duration p99 ~4s** (was 250ms).
- A trace for a slow order (Tempo): total ~3.9s, of which the **`reserve` HTTP span to inventory-service = ~3.7s**; the DB spans are a few ms.
- inventory-service RED: its own p99 is also elevated (~3.5s); error rate normal.
- inventory-service → Postgres: `HikariCP` active connections pinned at **max (10/10)**, `pending` threads > 0; DB CPU moderate.
- A marketing email blast went out ~20 min ago; inventory request Rate is **3× normal**.
- No deploys. order-service circuit breaker for inventory: **CLOSED** (calls are succeeding, just slow).
</details>

---

## Your move

1. Errors are normal but p99 is 4s. In the trace, **which span owns the latency**, and what does that tell you about where to look next?
2. The order-service→inventory breaker is **CLOSED**, not open. Why is that *consistent* with this symptom (and why didn't the breaker save you)?
3. inventory-service's DB pool is pinned at 10/10 with pending threads. Translate that into **why each request is slow** using USE (utilization/saturation).
4. "What changed?" points at a 3× traffic blast. Give two different fixes — one that treats the **symptom** fast, one that treats the **cause** — and the risk of each.
5. The connect/read timeouts on the inventory client are 1s/2s. Given a 3.7s span, what does that tell you — and is something misconfigured?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Localize with the trace (Q1).** The order's 3.9s is almost entirely the **`reserve` span to inventory-service (~3.7s)**; order-service's own DB work is milliseconds. So order-service isn't slow — it's **waiting on inventory-service**. Move the investigation downstream. (This is the whole value of distributed tracing: it turns "checkout is slow" into "inventory reserve is slow" in one click.)

**Breaker CLOSED is consistent (Q2).** The circuit breaker opens on **failures**, not slowness — these calls are **succeeding**, just slowly, so the failure rate stays under threshold and the breaker stays closed. A plain failure-rate breaker doesn't protect against a "slow but up" dependency (that's what a **slow-call-rate threshold** or a tighter timeout is for). So the breaker correctly didn't fire; it's not the tool for this.

**USE on inventory's DB pool (Q3).** HikariCP at **10/10 active** = the pool is **fully utilized**; **pending threads > 0** = **saturation** (requests are *queuing* for a connection). So each inventory request now spends most of its time **waiting to get a DB connection**, not doing DB work — that's the ~3.5s. The DB itself isn't the bottleneck (moderate CPU); the **connection pool** is the bottleneck, starved by 3× the normal concurrency.

**"What changed?" (Q4).** The marketing blast tripled inventory traffic. With a fixed pool of 10, 3× concurrency means lots of requests queue for a connection → latency balloons while throughput caps out.

**Root cause:** a traffic surge saturated inventory-service's DB connection pool (10/10, queueing), so reserve calls are slow, so checkout p99 is slow — a **saturation** problem, not an error or a code bug.

**Two fixes (symptom vs cause, Q4):**
- **Symptom, fast:** scale inventory-service out (more pods = more total pool capacity) and/or raise the pool size — *if* Postgres has headroom (`max_connections`!). **Risk:** raising pool size or pod count too far can **overwhelm Postgres** (connections are a DB-side limit too); you can move the bottleneck from the app pool to the DB.
- **Cause / durable:** rate-limit or smooth the burst (the order API already has a per-pod limiter; a shared/gateway limiter would cap global inventory load), add caching for the read path, and capacity-plan the pool against `max_connections`. **Risk:** rate-limiting sheds some load (429s) — a product decision.

**Timeouts (Q5).** The inventory **client** has connect 1s / read 2s. A **3.7s** span is longer than 2s, which means the time is spent **across retries** (3 attempts with backoff), not one 3.7s call — each attempt reads for up to 2s, times out or barely succeeds, and retries. Nothing is misconfigured; you're seeing retry amplification under saturation. (It also means retries are *adding* load to an already-saturated dependency — a reason to prefer shedding/caching over retrying here.)

**Confirm the fix:** inventory DB pool `pending` returns to 0, inventory p99 drops, the reserve span shrinks, checkout p99 returns to ~250ms, burn-rate alert resolves.
</details>

---

## The pattern

- **Slow ≠ broken.** Normal errors + high latency points at **saturation or a slow dependency**, not a crash or a bug. Different signals, different tools.
- **A trace localizes latency to a span in one click** — "checkout slow" becomes "inventory reserve slow" becomes "its DB pool is saturated." Always bisect latency with the trace before guessing.
- **A failure-rate breaker doesn't catch "slow but up."** For latency you need slow-call thresholds, timeouts, bulkheads, or capacity — know which guard catches which failure.
- **Pool pinned + queue building = saturation (USE).** The resource isn't erroring; it's full. Scale it *only* as far as the next resource down (the DB) can take.
- **Under saturation, retries hurt.** They add load to the thing that's already full.

> Grounded in: `InventoryClient` (`@Retry`+`@CircuitBreaker`, 1s/2s timeouts), inventory HikariCP pool, `slo-rules.yml` (latency counts against the SLO), Tempo tracing (`observation-enabled`).
</content>
