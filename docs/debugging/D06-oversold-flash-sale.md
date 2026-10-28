# D06 · The flash sale sold 5 units of a 3-unit product

> Skill: concurrency debugging. Related mechanism: [`../comprehension/05-overselling-concurrency/`](../comprehension/05-overselling-concurrency/), [`../comprehension/12-caching-cache-aside-failopen/`](../comprehension/12-caching-cache-aside-failopen/).

---

## Page (symptom)

A flash sale listed **3** PS5 units. After it ended, ops finds **5** orders in `CONFIRMED`/`PAID` for PS5, and the `product.quantity` row is at **−2**. Fulfilment can't ship 2 of them. This is money and trust, and it only happens **under burst load** — in staging, single requests never oversell.

**Before details: overselling is a concurrency bug. What's the one question that localizes it, and what's your first hypothesis?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `product.quantity = −2` for PS5-SLIM; 5 reservations exist.
- The overselling only reproduces under **concurrent** load; sequential calls are always correct.
- A deploy went out before the sale that "**sped up reservations by checking stock from the Redis cache first**."
- The reservation code path: a read of the product, a check, then a decrement.
- The k6 flash-sale test (`orders_confirmed == 3`) **passed in CI** on the commit *before* that deploy; it was **not** re-run on the deploy.
- `inventory_reservations_rejected_total` is far lower than expected for 300 buyers / 3 units.
</details>

---

## Your move

1. Overselling means two buyers both "won" the same unit. What's the **one property** of the stock-decrement step that, if missing, allows this — and what's the question that localizes the bug?
2. "Only under concurrency, never sequential" — why is that the fingerprint of a specific bug class, and which class?
3. The deploy added a **cache read before the decrement**. Walk the exact interleaving of two buyers that produces the oversell with this change.
4. ShopFlow's *correct* design decrements with `UPDATE … SET quantity = quantity − :qty WHERE … AND quantity >= :qty`. Why does that version make this interleaving impossible, and what did the cache change break about it?
5. The rejected-counter being low is a clue. What does it tell you, and how does it corroborate the root cause?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The one property (Q1): atomicity of check-and-decrement.** Overselling is only possible if "check there's enough stock" and "decrement it" are **not one atomic step** — if there's a window between reading the stock and writing the new value, two buyers can both read "enough" and both write. Localizing question: **"is the stock check and the decrement a single atomic operation against the source of truth, or a read-then-write?"**

**"Only under concurrency" (Q2)** is the textbook fingerprint of a **race condition / lost update** — a time-of-check-to-time-of-use (TOCTOU) gap. Sequential calls never interleave, so the gap is harmless; concurrent calls interleave in the gap. Any bug that "only happens under load" should make you think *shared mutable state + a missing lock/atomicity*, not "flaky."

**The interleaving with the cache change (Q3):**
1. Buyer A reads **cached** quantity = 3 → passes "≥1" check.
2. Buyer B reads **cached** quantity = 3 (cache not yet evicted) → also passes.
3. … and because the decision was made on the **stale, shared cache value** rather than a locked DB row, multiple buyers pass the gate before any authoritative decrement serializes them. The "check" now reads a value that lags reality and is the same for everyone in the window → everyone thinks there's stock.

**Why the correct version is immune (Q4).** `UPDATE … SET quantity = quantity − :qty WHERE sku=? AND quantity >= :qty` does the **check and the decrement in one atomic, row-locked statement** against Postgres. The DB **row lock serialises** concurrent buyers, and the `WHERE quantity >= :qty` **re-checks inside the same locked statement** — there is no read-then-write window. `rows affected = 0` means "no stock," atomically. The cache change broke this by **moving the stock decision out of that atomic statement** and onto a stale cached read — reintroducing the TOCTOU gap the SQL was designed to eliminate. **Rule violated: stock decisions must never read the cache; the cache is for the read-only product DTO, and writes *evict* it.**

**Rejected-counter clue (Q5).** With 300 buyers and 3 units, ~297 should be rejected (`InsufficientStockException` → `inventory_reservations_rejected_total`). It's **far too low**, meaning far fewer buyers hit the "no stock" path than should have — i.e. too many were **wrongly allowed through** by the stale-cache check instead of being rejected by the authoritative decrement. The metric corroborates "the gate let too many in."

**Root cause:** the deploy made the reservation path **decide on a cached stock value before the decrement**, creating a check-then-act race; under burst load multiple buyers passed the stale check and oversold. The atomic conditional `UPDATE` was bypassed as the decision-maker.

**Fix:**
1. **Revert** the cache-in-the-write-path change: the authoritative decrement is the single atomic `UPDATE`; the cache stays on the read DTO only and is **evicted** on reserve/release.
2. Re-run the **k6 `orders_confirmed == 3`** threshold as a **required gate on the deploy** (it would have caught this — it was green on the prior commit but never re-run on the change).
3. Reconcile the oversold orders (cancel/refund the 2 that can't be fulfilled); fix the −2 stock.

**Confirm:** k6 flash-sale shows exactly 3 confirmed, `product.quantity` never goes negative, rejected-counter ≈ buyers − stock.
</details>

---

## The pattern

- **"Only under concurrency, never sequential" = a race / lost update.** Look for a read-then-write on shared state with no lock or atomicity.
- **Localize with one question: is check-and-mutate a single atomic step against the source of truth?** If there's any gap, that's your bug.
- **Caches must never drive write decisions.** A cache is a stale copy; deciding "is there stock?" on it reintroduces the exact race the DB atomicity removed. Cache reads, evict on writes.
- **A business counter (rejections) that's "too low" is a signal** — it quantifies "the gate let too many through."
- **A guard test only guards if it runs on the change.** The k6 `count==3` gate existed; skipping it on the deploy is how the bug shipped. Gates must be required, not optional.

> Grounded in: `inventory-service/ProductRepository.decrementStock` (atomic `WHERE quantity >= :qty`), `ReservationService` (evict, not cache-in-write), `ProductController` (`@Cacheable` read DTO only), `loadtest/flash-sale.js` (`orders_confirmed: count==3`).
</content>
