# D17 · After a "make it safe" change, the flash sale throws a storm of 409s

> Skill: concurrency-control debugging (wrong lock for the contention profile). Related mechanism: [`../comprehension/28-concurrency-control-locks-jpa-db/`](../comprehension/28-concurrency-control-locks-jpa-db/), [`../comprehension/05-overselling-concurrency/`](../comprehension/05-overselling-concurrency/). Sibling of [D06](D06-oversold-flash-sale.md) (that one = *oversold* from too-little control; this one = *throughput collapse* from the wrong control).

---

## Page (symptom)

A well-meaning PR last sprint: *"Harden stock updates with optimistic locking."* The dev added a `@Version` column to the `product` entity and changed the inventory reserve path from the atomic conditional `UPDATE` to **load product → check quantity in Java → `setQuantity(q-1)` → save** (classic JPA read-modify-write, now guarded by `@Version`). Code review liked it; unit tests (single-threaded) pass; a light load test passed.

Today's flash sale — **3 units, ~300 buyers in one second** — and the graphs are ugly:
- `POST /orders` **409** rate is **huge** — far more than the ~297 "sold out" you'd expect; many buyers who *should* have gotten a clean "sold out" are instead seeing errors/retries.
- order-service and inventory-service **p99 latency spikes**; throughput on the reserve path **collapses**.
- DB CPU is **high** but the box isn't out of connections; no OOM, no crashloop.
- **Correctness is still fine** — exactly 3 units sell, never 4. Stock ends at 0.

So it's not overselling (D06's problem). It's that the sale "works" but is **slow and throwing a storm of conflict errors** under load it used to handle smoothly.

**Before details: the data is correct but the system thrashes under contention right after switching to optimistic locking on the hottest row. What's the mechanism?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- inventory-service logs: floods of `ObjectOptimisticLockingFailureException` / `StaleObjectStateException` on the `product` row for the hot SKU.
- The reserve path now does **two** statements: `SELECT ... FROM product WHERE sku=?` then `UPDATE product SET quantity=?, version=? WHERE id=? AND version=?`.
- Metric: for the hot SKU, **UPDATE attempts ≫ successful updates** — a large majority of attempts update **0 rows** (version moved on) and throw.
- If a retry loop exists, retries-per-request is high; if not, the failure surfaces as an error to the caller.
- The **same** flash sale on the previous release (atomic conditional `UPDATE`) showed ~3×201 + ~297×409, low latency, low DB CPU.
- Only change in the diff: `product` got `@Version` + the reserve path became load-modify-save. No infra change.
- Other, low-contention entities (orders) using `@Version` are **fine** — the storm is only on the **hot SKU**.
</details>

---

## Your move

1. All 300 buyers hit **one** product row. With optimistic `@Version` on a load-modify-save, what happens to the 299 who read the same version and try to write — statement by statement?
2. Why does this show up as **high DB CPU + collapsed throughput**, not as wrong data? What work is being done and thrown away?
3. The **same** `@Version` on the orders table causes no storm. What's different about that workload that makes optimistic locking correct there and wrong here?
4. Give the one-line fix that restores throughput, and explain why it has **no** conflict storm even though contention is identical.
5. If product policy *required* keeping `@Version` (say, for an admin edit path), how would you serve the high-contention reserve path **without** the storm — can both coexist?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The mechanism (Q1).** Optimistic locking assumes conflicts are **rare** — it does no locking, lets everyone proceed, and makes the **write** fail if the version moved. Under a flash sale, conflicts are **the norm**: ~300 requests `SELECT` the product at **version N**, all compute `quantity-1` in Java, all try `UPDATE ... WHERE version=N`. **Exactly one** wins (version → N+1); the other **~299 update 0 rows and throw** `OptimisticLockingFailureException`. Then (with a retry loop) the survivors re-read version N+1 and race again — one wins, the rest throw — and so on. Each unit sold costs a **full round of read-compute-fail** across the whole crowd: O(buyers) wasted attempts per successful decrement. That's a **retry storm / livelock-ish thrash**.

**Why CPU-high, throughput-collapsed, data-correct (Q2).** Every one of those 299 failed attempts still did real work — a `SELECT`, a Java check, an `UPDATE` that the DB evaluated and **rejected** on the version predicate, a transaction rolled back, an exception built and thrown. That's a lot of DB and app work **producing nothing** (all thrown away) → **high CPU, low goodput**. The data stays correct because the version check *does* prevent the lost update — optimistic locking is **correct**, it's just **catastrophically inefficient** for this contention profile. "Correct but thrashing" is the tell.

**Why orders are fine (Q3).** The orders table is **low contention** — two people rarely mutate the *same* order at the *same* instant (the odd double-cancel). There, optimistic locking is ideal: no lock held on the overwhelmingly common no-conflict path, and the rare collision cleanly yields one 409. The variable isn't the mechanism, it's the **contention on a single row**: optimistic shines when conflicts are rare, and **collapses when a single hot row is contended by hundreds at once**. Same tool, opposite workload, opposite result.

**Root cause:** the reserve path was switched to **optimistic locking on the single hottest row in the system**. Optimistic control is the wrong family for an extreme-contention single-column counter — it converts "take a µs row lock and decrement" into "hundreds of read-compute-fail-retry rounds," so the sale is correct but slow and 409-storming.

**The fix (Q4).** Restore the **atomic conditional `UPDATE`**: `update product set quantity = quantity - :qty where sku = :sku and quantity >= :qty` (one `@Modifying` statement, rows-affected signal). It has **no** conflict storm because the check lives **inside** the write: the DB takes a **row lock for the microseconds of that one statement**, serialises the 300 buyers cleanly, each `UPDATE` re-evaluates `quantity >= qty` against the current value, and a genuine sold-out returns **rows-affected 0** (a clean, cheap "sold out" → 409 *business* outcome) — **not** a thrown optimistic-lock exception. One statement, one short lock, no read-modify-write gap, no retries. (This is exactly what ShopFlow shipped **before** the "hardening" PR — `inventory/ProductRepository.decrementStock`.)

**Coexisting with `@Version` if required (Q5).** Yes — pick the control **per access path**, not per entity:
- **Reserve (hot, high-contention):** keep the **atomic `@Modifying` UPDATE** — bypasses the entity/version entirely, just moves the counter.
- **Admin edit (rare, whole-entity):** keep **`@Version`** optimistic for the load-modify-save admin screen, where conflicts are rare and you want "someone edited this under you → 409."
The atomic decrement doesn't touch `@Version` semantics for the admin path; they guard different operations on the same row. The lesson of topic 28: **the contention profile of the operation, not the table, chooses the concurrency control.**

**Confirm the fix:** 409s drop back to ~297 *clean* sold-outs (business, not exceptions), optimistic-lock exceptions vanish from logs, DB CPU falls, reserve p99 returns to baseline, still exactly 3 sold.
</details>

---

## Reproduce / test (apne haath se)
```bash
# the current (correct) design is the atomic UPDATE — confirm it has NO optimistic storm:
make up && TOKEN=$(make -s token)
# 20 parallel reserves for 3 units -> 3x201, 17x409 (clean sold-out), ZERO optimistic-lock errors:
{ for i in $(seq 1 20); do
    curl -s -X POST http://localhost:8082/api/v1/reservations \
      -H 'Content-Type: application/json' -d "{\"orderRef\":\"r-$i\",\"sku\":\"PS5-SLIM\",\"quantity\":1}" \
      -o /dev/null -w "%{http_code}\n" &
  done; wait; } | sort | uniq -c
docker compose -f shopflow/docker-compose.yml logs inventory-service | grep -ci "OptimisticLock"   # -> 0

# to SEE the storm (teaching): temporarily switch reserve to load-modify-save guarded by @Version,
# re-run the 20-parallel burst, and watch OptimisticLockingFailureException flood + latency rise,
# while the final stock is still exactly 0 (correct but thrashing). Then revert.
```
**Proved:** the shipped atomic `UPDATE` serialises the burst with clean 409s and **zero** optimistic-lock exceptions; swapping to `@Version` RMW on the hot row reproduces the storm (correct data, collapsed throughput).

## The pattern

- **Correct-but-slow under contention = wrong concurrency control, not a data bug.** A flood of optimistic-lock failures on one hot row screams "optimistic was the wrong family here."
- **Optimistic locking assumes rare conflicts.** On a single hot row hit by hundreds, it degenerates into a read-compute-fail-retry storm — O(crowd) wasted work per successful write.
- **The contention profile — not the table — picks the tool.** Rare conflict → optimistic; hot single-column counter → atomic conditional `UPDATE` (no lock held, rows-affected signal); hot row + short critical section + multi-step → pessimistic `FOR UPDATE`.
- **"Hardening" can de-scale.** Adding a lock/version because it *sounds* safer can wreck throughput while adding no correctness the atomic `UPDATE` didn't already have.
- **Different paths, different control, same row.** `@Version` for the rare admin edit and an atomic `UPDATE` for the hot reserve can coexist.

> Grounded in: `inventory/ProductRepository.decrementStock` (the correct atomic `UPDATE`), `Order.java` `@Version` (optimistic where contention is low), topic 28 (the three families) and topic 05 (overselling). The `@Version`-on-stock regression is a reasoned scenario (not in the repo) illustrating the wrong-tool failure.
