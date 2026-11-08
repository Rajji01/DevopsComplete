# D16 · Inventory says 5 units, the warehouse has 2 (phantom stock)

> Skill: race-idempotency / compensation debugging. Related mechanism: [`../comprehension/05-overselling/`](../comprehension/05-overselling/), EASY-NOTES §1/§2a, VISUAL-FLOWS #17. Born from Walk 55.

---

## Page (symptom)

No alert fired. A weekly manual stock reconciliation finds `product.quantity` for three SKUs is **higher than the physical warehouse count** — by exactly 2–3 units each. Days later, those "units" sell: real customers pay for stock that doesn't exist, and support has to refund and apologise. The oversell guard (`decrementStock`'s conditional UPDATE) is in place and its concurrency test is green.

**Before details: the oversell guard protects the stock NUMBER. What could corrupt the number itself?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- The drifted SKUs all had **cancelled or payment-failed orders** in the affected window; drift per SKU ≈ the quantity of ONE such order (not a multiple of many).
- order-service logs around those orders: both a **customer cancel** (HTTP) and a **reconciler pass** settling the same order within the same second — or two reconciler pods (HPA `minReplicas: 2`) ticking together.
- inventory-service logs: `released N x SKU for order X` appears **twice** for the same orderRef, milliseconds apart, from two different pods/threads.
- `reservation` rows for those orders: `status = RELEASED` (looks normal; no error logged anywhere).
- `Reservation` entity has **no `@Version`**; `release()` at the time: `findByOrderRef` → filter `RESERVED` → in-memory `status = RELEASED` + `incrementStock(+qty)` in one transaction.
- The idempotency test `releaseRestoresStockOnlyOnce` (sequential: release twice, assert stock once) is green.
</details>

---

## Your move

1. The release path has a status guard and a green idempotency test. Walk the **two-transaction interleaving** that still increments stock twice. Why does no error appear anywhere?
2. The sequential test is green and the code is "idempotent". What *kind* of idempotency does a read-then-write guard buy, and which kind did this need? (Two words each.)
3. Three fixes were on the table: `@Version` on `Reservation`, `SELECT ... FOR UPDATE` on the read, or a conditional `UPDATE ... WHERE status='RESERVED'` gating the increment. Pick one and argue against the other two *in this codebase* (hint: what does order-service do when a 500 comes back from release? what pattern does inventory already trust for oversell?).
4. Write the deterministic test that fails before the fix and can never flake after it (what synchronisation primitive, how many threads, what's the assert?).
5. No alert fired for days. Design the cheapest detection for stock drift — what would you compare against what, and where would it run?

---

## Walkthrough

<details>
<summary>Open after you've argued your answers</summary>

1. **The interleaving:** T1 (reconciler pod A) and T2 (customer cancel) both `SELECT` the reservation — both see the committed `RESERVED` (T1 hasn't committed yet; READ_COMMITTED shows the last committed version). Both pass the filter. T1 flips status in memory, runs `incrementStock(+3)`, commits. T2's status UPDATE waits on T1's row lock, acquires it after commit, writes `RELEASED` over `RELEASED` (no condition → 1 row, no error), then runs `incrementStock(+3)` again. **Without `@Version`, the second write is last-writer-wins, so nothing throws** — the only trace is the doubled increment and the duplicated log line.
2. A read-then-write guard buys **replay idempotency** (same call *later* sees RELEASED and no-ops). This needed **race idempotency** (same call *overlapping*). The green sequential test proved the first and said nothing about the second.
3. The repo chose the **conditional UPDATE** (`ReservationRepository.transition(orderRef, RESERVED, RELEASED)`; only the caller that got `1` increments). Against `@Version`: needs a schema migration, and the loser's `OptimisticLockingFailureException` becomes an HTTP 500 that order-service's reconciler reads as "inventory down" — aborting its batch (Walk 53's bug, re-created one service over). Against `FOR UPDATE`: it serialises correctly but holds the row lock across the increment for every release (even uncontended ones), and pessimistic locks in a path called by HTTP *and* reconcilers invite lock-wait pileups during batches. The conditional UPDATE is also the **same pattern `decrementStock` already uses** for the scarier oversell race — one problem, one idiom.
4. `concurrentReleasesRestoreStockOnlyOnce`: a `CyclicBarrier(2)`, two threads calling `release(orderRef)` for one reservation of quantity 3, join both, assert stock == the pre-reserve value. Before the fix it reads `before + 3` whenever both reads precede the first commit (the barrier makes that the common case); after the fix, every interleaving ends at `before` because exactly one `transition` returns 1.
5. Cheapest detection: a scheduled query comparing `product.quantity + SUM(active reservations)` against the seeded/known total per SKU (the invariant the system is supposed to conserve), exported as a gauge with an alert on `!= 0`. Runs where the reconciler already runs; it would have caught this in one tick instead of one week.

**The transferable pattern:** every "X is idempotent, double-call is safe" claim has two tiers — replay and race. If the guard is a read, you have only the first. Make the state transition itself atomic (conditional UPDATE / unique constraint / version check) and gate the side effect on *winning* it — and test it with a barrier, not a loop.
</details>

---

## Reproduce / test (apne haath se)

```bash
# 1. The deterministic race test (the fix's proof) — JDK 21:
cd shopflow && ./mvnw -pl inventory-service test -Dtest='InventoryServiceApplicationTests#concurrentReleasesRestoreStockOnlyOnce'

# 2. Re-create the bug: revert the guard and watch the same test fail
#    (in ReservationService.release, replace the transition() gate with the old shape:
#     r unused + always incrementStock) -> run the test again -> stock comes back before+3.

# 3. Manual two-terminal race against the compose stack (timing-dependent, try a few times):
docker compose up -d
ORDER=$(uuidgen)
curl -s -X POST localhost:8082/api/v1/reservations -H 'Content-Type: application/json' \
  -d "{\"orderRef\":\"$ORDER\",\"sku\":\"IPHONE-15\",\"quantity\":3}"
# terminal A and terminal B, as close to simultaneously as you can:
curl -s -X DELETE localhost:8082/api/v1/reservations/$ORDER &
curl -s -X DELETE localhost:8082/api/v1/reservations/$ORDER &
wait
curl -s localhost:8082/api/v1/products/IPHONE-15   # quantity must equal the pre-reserve value
```
