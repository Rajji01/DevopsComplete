# D21 · Random "deadlock detected" under load — only when traffic is high, never in a test

> Skill: deadlock debugging (lock-ordering on multi-row writes). Related mechanism: [`../comprehension/28-concurrency-control-locks-jpa-db/`](../comprehension/28-concurrency-control-locks-jpa-db/), [`../comprehension/30-replication-multi-instance-db/`](../comprehension/30-replication-multi-instance-db/).

---

## Page (symptom)

A feature that updates **two rows in one transaction** (think a `transfer(from, to, amount)` moving stock/credit between two accounts) starts failing **intermittently** under load: a small fraction of requests fail with Postgres **`deadlock detected`** / Spring `CannotAcquireLockException`, and the request 500s (or retries). It happens **only when concurrency is high**; a single-threaded test never triggers it, and it's **not reproducible on demand** — it depends on *which* two rows each concurrent request happens to touch and in *what order*.

The data ends up **consistent** (the DB rolled one transaction back), so nothing is corrupted — but a slice of users get errors during peak, and no one can reliably reproduce it.

**Before details: two transactions, each updating two rows in one transaction, failing intermittently only under load. What is the precise condition that creates a deadlock, and why is it load-and-ordering dependent?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- Postgres logs: `ERROR: deadlock detected` with a `DETAIL:` naming two processes each **waiting for a lock the other holds**, on two different rows of the same table.
- The failing transactions each do: `UPDATE account SET ... WHERE id = :a` then `UPDATE account SET ... WHERE id = :b` — i.e. **two row locks**, acquired in the order the **parameters** came in.
- The incidents cluster at **peak concurrency**; off-peak it's basically never.
- Pairs that deadlock are **mirror images**: one request transfers A→B, another B→A, at the same moment.
- Data integrity is fine (one side rolled back); the cost is **errors + retries**, not corruption.
- No deploy; the feature has been live; volume just grew.
</details>

---

## Your move

1. Write the exact interleaving of two concurrent transactions that produces the deadlock, naming which lock each holds and waits for.
2. Why does it only show up **under load**, and why is it **unreproducible** single-threaded?
3. The DB "handles" it by killing one transaction (`deadlock detected`). Is that good or bad — what did it prevent, and what did it cost?
4. Give the **primary** fix (prevents deadlocks from forming) and a **secondary** fix (survives the ones that slip through). Why do you want both?
5. A dev "fixes" it by wrapping the transfer in a **pessimistic table-level lock** so only one transfer runs at a time. Why does that remove the deadlock but create a worse problem at scale?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The interleaving (Q1).** Transaction T1 = transfer **A→B**: it `UPDATE`s row **A** (takes A's lock), then tries to `UPDATE` row **B**. Transaction T2 = transfer **B→A**, running at the same instant: it `UPDATE`s row **B** (takes B's lock), then tries to `UPDATE` row **A**. Now T1 **holds A, waits for B**; T2 **holds B, waits for A** — a **cycle**. Neither can proceed; that's a **deadlock**. Postgres's deadlock detector notices the cycle and **aborts one** of them with `deadlock detected`.

**Why load-and-ordering dependent (Q2).** A deadlock needs **two transactions acquiring the same two locks in *opposite* orders at overlapping times**. Single-threaded, there's never a second holder, so no cycle can form — that's why tests pass. Under **high concurrency**, the probability that two mirror-image transfers (A→B and B→A) overlap *and* grab their first locks before either grabs the second rises sharply, so it surfaces only at peak. It's **probabilistic** (timing × which rows), hence "random" and unreproducible on demand.

**Is the DB kill good or bad (Q3).** It's **correct and necessary**: without it the two transactions would **wait forever** (a true hang). Aborting one **breaks the cycle** and keeps the system live — the DB chose availability of the other transaction over both hanging. The **cost** is that the aborted transaction's work is thrown away and surfaces as an **error/retry** to that user. So the detector prevents a hang; it doesn't prevent the *waste* — you still want to stop deadlocks from forming.

**Primary + secondary fix (Q4).**
- **Primary — consistent lock ordering:** always acquire the two row locks in a **deterministic global order**, e.g. **lower `id` first**. If every transfer locks `min(a,b)` then `max(a,b)`, a cycle is **impossible** — T1 and T2 both try to lock the lower id first, so one simply waits for the other (an orderly queue), never a mutual wait. (In code: sort the two account ids before issuing the updates; or do both legs as single atomic guarded `UPDATE`s but still in sorted order.)
- **Secondary — retry on deadlock:** deadlocks can still arise from other paths, so make the transaction **idempotent/retryable** and **retry** on `deadlock detected` / `CannotAcquireLockException` (small bounded retries with jitter). The transaction is short and the retry almost always wins.
- **Both** because ordering **prevents** the common case cheaply, and retry **survives** the residue — defense in depth. (Keep transfers in **one transaction** so money/stock is never half-moved, and keep that transaction **short** — no remote calls inside, per D17/topic 04.)

**Why the table lock is worse (Q5).** A table-level (or global) lock serialises **all** transfers — exactly **one** runs at a time. It removes the deadlock (no two transactions hold different row locks anymore) but it **destroys throughput**: every transfer now queues behind a single global lock regardless of whether they touch the **same** accounts, so unrelated transfers (C→D while A→B runs) are needlessly blocked. You traded a rare, retryable deadlock for a **permanent serialization bottleneck** — a convoy that caps the whole feature at single-threaded speed. The right granularity is **per-row locks in a consistent order**, not one big lock.

**Root cause:** two-row writes acquired their locks in **parameter order** (not a consistent global order), so mirror-image concurrent transactions (A→B vs B→A) formed a lock cycle under load — an ordering bug that only manifests at concurrency.

**Confirm the fix:** after sorting lock acquisition by id (+ bounded retry), `deadlock detected` disappears from the logs under the same peak load, transfer error rate drops to ~0, throughput stays high (unrelated transfers run in parallel), data still consistent.
</details>

---

## Reproduce / test (apne haath se)
```bash
# ShopFlow has no transfer today, but you can reproduce the deadlock mechanism on ANY two rows with two psql sessions:
# session 1:                                   session 2:
#   BEGIN;                                        BEGIN;
#   UPDATE product SET quantity=quantity         UPDATE product SET quantity=quantity
#     WHERE sku='PS5-SLIM';   -- locks PS5          WHERE sku='PIXEL-9';   -- locks PIXEL
#   UPDATE product SET quantity=quantity         UPDATE product SET quantity=quantity
#     WHERE sku='PIXEL-9';    -- WAITS on s2        WHERE sku='PS5-SLIM';  -- WAITS on s1 -> DEADLOCK
# Postgres aborts one with: ERROR: deadlock detected
docker compose -f shopflow/docker-compose.yml exec postgres psql -U inventory -d inventory   # run the two sessions
# the fix in code: sort the two ids/SKUs before locking so both sessions take the SAME one first -> no cycle.
```
**Proved:** two sessions locking the same two rows in **opposite** order deadlock; locking in a **consistent** order (both take the lower key first) turns the cycle into an orderly wait.

## The pattern

- **Deadlock = two transactions, same two locks, opposite order, overlapping.** Break any one condition (ordering) and it can't form.
- **Consistent global lock ordering (e.g. lowest id first) is the primary fix** — it makes a cycle impossible without serializing unrelated work.
- **Retry on `deadlock detected` is the secondary net** — short idempotent transactions retry cheaply.
- **Intermittent + load-only + unreproducible-single-threaded = a concurrency/ordering bug.** The timing dependence is the fingerprint.
- **Don't "fix" a lock problem with a bigger lock.** A table/global lock removes the deadlock by destroying parallelism — the convoy is worse than the retry.
- **Keep the transaction short and single** — both rows in one txn (atomicity), no remote calls inside (D17).

> Grounded in: ShopFlow's row-lock mechanics (atomic `UPDATE`, topic 28) and DB-per-service/multi-instance writes (topic 30). The two-row `transfer` is a reasoned scenario (ShopFlow has no such endpoint today) — the lock-ordering lesson applies to any multi-row write.
