# D19 · "I placed an order but my list is empty" — only sometimes, and never in staging

> Skill: the undiagnosable-in-prod Heisenbug (replication lag / read-your-writes). Related mechanism: [`../comprehension/30-replication-multi-instance-db/`](../comprehension/30-replication-multi-instance-db/), [`../comprehension/29-silent-data-corruption-heisenbugs/`](../comprehension/29-silent-data-corruption-heisenbugs/).

---

## Page (symptom)

A trickle of support tickets, all the same shape: *"I placed an order, the confirmation showed, I went to My Orders — and it wasn't there. I refreshed and it appeared."* It happens to **maybe 1–3%** of users, **not** always the same ones, and **never** when you try to reproduce it. Staging is completely clean. The logs show the `POST /orders` returning **201** and the later `GET /orders` returning **200 with an empty (or short) list** — **no error anywhere**.

Recently the team **added read replicas** and routed `GET` traffic to them to take read load off the primary (the topic-26 scaling move). Writes still go to the primary.

**Before details: a write succeeds, an immediate read by the same user comes back missing the thing they just wrote, no errors, intermittent, unreproducible in staging. What is this, structurally?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `POST /orders` → **201**, order row **exists on the primary** (verified: `psql` on the **primary** shows it).
- The same user's `GET /orders` a moment later → **200**, list missing that order. A `GET` a few seconds later → the order is **there**.
- Reads are routed to **async read replicas**; writes to the **primary**.
- Replica lag metric (`pg_stat_replication` / `replica_lag_seconds`) shows **spikes of 0.5–3s** under load — and the tickets **correlate** with those spikes.
- It's **intermittent**: only when the user reads **within the lag window** after their write; most users read slightly later and see it.
- **Staging has no replica** (single DB), so the read-after-write is always consistent there — nothing to reproduce.
- No code error, no exception, no 5xx. Data is **correct** on the primary at all times.
</details>

---

## Your move

1. The order is on the primary but missing from the user's immediate read. Which node served that read, and what state was it in?
2. Why is this **structurally impossible to reproduce in staging**, and why does it hit only **1–3%** of users?
3. This produces **no error** — why, and why does that make it one of the hardest bugs to triage from logs alone?
4. Give two correct implementations of "read-your-writes" and the trade-off of each.
5. A dev proposes "just add a 500ms sleep before the redirect to My Orders." Why is that both wrong and fragile?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Which node, what state (Q1).** The write went to the **primary** (201, row present). The immediate `GET` was routed to an **async read replica** that had **not yet replayed** the primary's WAL for that new row — it was **lagging** by a fraction of a second to a few seconds. So the replica correctly returned what *it* had: a list **without** the just-created order. A later read, after the replica caught up, shows it. The data is never wrong — the **read hit a copy that was behind**.

**Why unreproducible / why 1–3% (Q2).** **Staging has a single database** (no replica), so every read-after-write is served by the same node that took the write — **always consistent**, nothing to reproduce. In prod, you only see the bug if your read lands on a replica **during the lag window** right after your write; most users navigate a beat later (or their read happens to hit a caught-up replica), so only the slice whose timing falls inside the lag — **~1–3%**, varying with load-driven lag — is affected. It's a **timing × topology** bug: it needs both the replica *and* the sub-second race, which only prod has.

**Why no error (Q3).** Nothing failed. The write committed, the read succeeded, both returned 2xx — the replica **faithfully** returned its (slightly stale) state. There is no exception to catch, no 5xx to alert on, no stack trace; the only "signal" is a **semantic** one ("the thing I just made isn't listed"), which looks like a user mistake or a UI glitch, not a backend fault. That's why it's brutal from logs: you must **correlate the write and the read, tag which DB node served each, and overlay replica lag** — none of which a plain error log shows. (This is topic 29's "absence of an exception is not evidence of correctness," made concrete.)

**Root cause:** reads that a user issues **immediately after their own write** are being served from an **asynchronous replica that lags the primary**, so they miss the just-written row — a **read-your-writes** violation introduced the moment read replicas were added. Correct data, wrong (stale) copy.

**Fix — read-your-writes, two correct ways (Q4):**
- **(i) Primary-pin after write (sticky):** for a short window after a user performs a write (tracked by a session flag or a short-lived cookie/token), route **that user's** reads to the **primary**. Simple, bounded extra primary load; the window must cover worst-case lag.
- **(ii) LSN / log-position wait (bounded staleness):** capture the **log position (LSN)** of the user's write; when serving their read, either pick a replica that has **replayed ≥ that LSN** or **wait** until one has (up to a timeout, then fall back to primary). More precise (no blanket primary load), more plumbing.
Cross-user and aggregate reads (someone else's orders, dashboards) stay on replicas — only **read-your-writes** needs this. (Caching the user's own just-written result client-side, or returning the created resource in the POST response and merging it into the list, also mitigates the UX.)

**Why the sleep is wrong (Q5).** A fixed `500ms` sleep (a) **slows every user** to paper over a 1–3% case, (b) is a **guess** — lag spikes to 3s under load, so 500ms still fails sometimes, and (c) **doesn't scale** — it couples UX latency to an unbounded, load-dependent replica lag you don't control. It treats a **consistency-routing** problem with a **timing hack**; the correct fix routes the read to data that's actually current (primary-pin or LSN-wait), not "hope the lag is under half a second."

**Confirm the fix:** tag each query's served node + capture lag in traces; after routing read-your-writes to the primary (or LSN-wait), the "my order is missing" tickets stop, replica read-load stays offloaded for everyone else, and a synthetic "write then immediately read" check passes even during lag spikes.
</details>

---

## Reproduce / test (apne haath se)
```bash
# dev is single-node, so this bug CANNOT occur locally (that's the whole point — it needs a replica).
# to SEE it, you need a primary + async replica (managed PG, or two local postgres with streaming repl):
#   1) write a row to PRIMARY
#   2) immediately SELECT from the REPLICA  -> row missing during the lag window
#   3) SELECT again after ~1-2s             -> row present
# measure the window:
#   psql (primary):  select pg_current_wal_lsn();
#   psql (replica):  select pg_last_wal_replay_lsn();   -- compare: replica behind = the staleness gap
# in prod: tag every query's served node + replica_lag in the trace; correlate tickets with lag spikes.
```
**Proved (conceptually):** the bug lives only where an async replica + a read-immediately-after-write race coexist; dev's single node makes it structurally invisible — exactly why staging never caught it.

## The pattern

- **An intermittent, no-error, staging-clean bug after adding replicas = replication lag / read-your-writes.** The shape (write ok, immediate read stale, later read fine) is the fingerprint.
- **Single-node environments can't reproduce multi-node consistency bugs.** If staging has no replica, it certifies a topology you don't run — the bug is structural, not random.
- **No exception ≠ no bug.** A correct 2xx read of a stale replica is the hardest triage; you need **node-tagged queries + lag metrics in traces**, not error logs.
- **Fix with routing, not timing.** Primary-pin or LSN-wait for read-your-writes; never a fixed sleep.
- **Only read-your-writes needs the primary.** Keep everything else on replicas so you keep the offload.

> Grounded in: ShopFlow's topology (dev single-node; prod RDS with replicas as the scale step, `HLD §5` / `ARCHITECTURE.md`), topic 30 (replication) and topic 29 bug 5 (stale read). The replica setup is the reasoned scaling state, not the current repo.
