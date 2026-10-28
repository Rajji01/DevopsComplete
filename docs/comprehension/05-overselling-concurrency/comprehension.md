# 05 · Overselling 3 PS5s to 300 buyers — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `inventory-service/…/ProductRepository.decrementStock`, `ReservationService.reserve`, `order-service/…/OrderController` (`@RateLimiter`), `loadtest/flash-sale.js`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's flash-sale design + its load test. Read once, then answer.*

A flash sale puts **3** PS5 units on sale and 300 buyers hit "buy" in the same second. The system must confirm **exactly 3** orders — never 4, never 2 — and must not fall over.

The naive approach reads the stock, checks it in Java, then writes the new value:

```java
Product p = repo.findBySku(sku);        // read: quantity = 3
if (p.getQuantity() >= qty) {           // check in app memory
    p.setQuantity(p.getQuantity() - qty);
    repo.save(p);                       // write
}
```

Under concurrency this **oversells**. Two threads both read `quantity = 3`, both pass the check, both write `2` — two units sold against one. This is a lost-update race: the check and the write are not atomic.

ShopFlow does the check **and** the decrement in **one SQL statement**:

```sql
update product set quantity = quantity - :qty where sku = :sku and quantity >= :qty
```

The database takes a **row lock** for the duration of the `UPDATE`, so concurrent buyers are **serialised** on that row, and the `where quantity >= :qty` clause **re-checks the stock inside the same atomic statement**. The call returns the **number of rows updated**: `1` means the decrement happened, `0` means either the SKU doesn't exist or there wasn't enough stock. There is no read-modify-write window for two threads to slip through. `ReservationService.reserve` wraps this: decrement + insert the reservation row in **one transaction**, so a failed insert rolls the stock back; and it's idempotent — a second `reserve` with the same `orderRef` returns the existing reservation instead of decrementing again.

Two more defences sit in front. The order API is annotated `@RateLimiter(name = "orders")` — **50 order creations per second per pod**, excess gets a `429` (reads are unlimited). And the **k6 load test encodes the guarantee as a pass/fail threshold**: 300 buyers, 3 units, and `orders_confirmed: ['count==3']` — the build fails if even one extra order confirms. The test also asserts `http_req_failed < 1%` while treating `409` (sold out) and `429` (throttled) as **normal business outcomes, not errors**, browse `p99 < 300ms`, and order `p95 < 800ms`.

---

## Questions

### A — Literal
1. In the naive code, what exact sequence of two threads produces an oversell?
2. What are the two things the single `UPDATE` statement does atomically, and what does its **return value** of `0` mean?
3. What does `orders_confirmed: ['count==3']` in the load test cause to happen if 4 orders ever confirm?

### B — Inference
4. The `UPDATE` has no explicit `SELECT ... FOR UPDATE` and no application-level lock. Where does the mutual exclusion that prevents overselling actually come from?
5. The load test counts `429` and `409` responses but deliberately **excludes** them from `http_req_failed`. Why would counting them as failures give you a misleading red build?

### C — Trade-off
6. This uses the atomic-UPDATE (a form of **optimistic** check in one statement) rather than `SELECT ... FOR UPDATE` then update (**pessimistic**), or a `@Version` optimistic-lock retry loop. Why is the single conditional `UPDATE` a good fit for "decrement stock" specifically?
7. The rate limiter is **50/s per pod**, not a global limit. What does "per pod" fail to bound, and what would you reach for if you needed a true global cap?

### D — Transfer / extension
8. The same `reserve` can be retried by order-service (timeout then retry). Walk through why the retry does **not** decrement stock twice, citing the two mechanisms involved.
9. Marketing wants an **oversell-by-5%** mode for a different product (accept slightly more orders than stock, to cover expected cancellations). What single change to the SQL achieves it, and what new risk does it introduce that the strict version doesn't have?

### E — Spot-the-flaw
10. A dev "improves performance" by caching the product's stock in Redis and checking that cached value before the `UPDATE`, to reject sold-out buyers faster. Under the flash sale, what can now go wrong that the pure `UPDATE` never allowed — and what is the one rule about the cache this violates?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. Thread 1 reads `quantity = 3`; thread 2 reads `quantity = 3` (before thread 1 writes); both pass `>= qty`; thread 1 writes `2`; thread 2 writes `2`. Two units sold, stock only decremented once — a **lost update**.
2. Atomically: **(a)** re-checks there is enough stock (`where quantity >= :qty`) and **(b)** decrements it (`set quantity = quantity - :qty`) — in one locked statement. Return value **`0`** = nothing was updated: either the SKU doesn't exist **or** there wasn't enough stock.
3. The threshold fails, so the **k6 run exits non-zero and the CI job (load-test gate) fails the build** — overselling can't merge.
</details>

<details>
<summary>B — Inference</summary>

4. From the **database row lock** that the `UPDATE` takes on that product row for the duration of the statement. Concurrent `UPDATE`s on the same row are serialised by the DB, and because the stock check is *inside* that same atomic, locked statement, there's no read-then-write gap for a second thread to exploit. The app writes no lock code; the DB provides the mutual exclusion.
5. Because `429` (rate-limited) and `409` (sold out) are **correct behaviour under a flash sale** — the system is *supposed* to shed load and reject buyers once stock is gone. If you counted them as failures, a perfectly working flash sale (297 buyers correctly rejected/throttled) would show a ~99% "failure rate" and fail the build, hiding whether any *real* (5xx) errors occurred. The threshold isolates genuine server faults.
</details>

<details>
<summary>C — Trade-off</summary>

6. "Decrement stock" is a **single-row, high-contention, simple** operation with a natural guard expressible in SQL (`quantity >= qty`). The conditional `UPDATE` resolves the whole check-and-act in one round-trip with one lock held only for that statement — no separate `SELECT`, no app-side retry loop, minimal lock duration, and it scales well under 300 concurrent buyers. `SELECT ... FOR UPDATE` needs a second statement and holds the lock longer; a `@Version` retry loop would thrash under high contention (every loser retries). For this exact shape, the one-statement conditional update is the cleanest and fastest.
7. "Per pod" fails to bound the **total** rate across a horizontally-scaled deployment: with 4 pods you can take ~200/s cluster-wide, and the number drifts as pods autoscale. For a true global cap you'd use a **shared/distributed limiter** — e.g. a Redis-backed token bucket or an API-gateway/ingress rate limit in front of all pods. (ShopFlow notes this as the next step; per-pod protects the DB/inventory adequately for now.)
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Two mechanisms: **(1)** `ReservationService.reserve` first does `findByOrderRef(orderRef)` — on a retry the reservation already exists, so it **returns the existing one without decrementing**. **(2)** Even if two retries raced before either inserted, the decrement and the reservation insert are in **one transaction**, and the reservation is keyed by `orderRef` (unique), so the second insert can't create a duplicate. So a retried `reserve` is idempotent: stock moves at most once per `orderRef`.
9. Change the guard to allow stock to go slightly negative, e.g. `where sku = :sku and quantity > -(ceil(initial*0.05))` — in practice, relax/raise the floor so a few extra decrements succeed (or drop the `>= :qty` guard and allow a small negative). **New risk:** you can now **genuinely oversell** — if cancellations don't materialise, you've confirmed orders you can't fulfil, so you need a downstream process to cancel/refund the overflow. The strict version can never promise stock it doesn't have; the oversell mode trades that safety for conversion.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The cached stock value is **stale** — under a flash sale it lags the real quantity by milliseconds. Checking it before the `UPDATE` doesn't *cause* an oversell (the atomic `UPDATE` is still the source of truth and still returns `0` when truly out), but it **can both wrongly reject buyers while stock still exists** (cache says 0, DB says 1) **and wrongly admit buyers to the UPDATE when it says in-stock** — adding load and inconsistent UX. The rule it violates: **stock decisions must never read the cache; only the atomic `UPDATE` decides.** ShopFlow caches only the read-only `GET /products/{sku}` DTO and *evicts* on reserve/release — the authoritative decrement never consults the cache.
</details>

---

## Tod ke dekho — Local (docker compose + k6)

Goal: run the real flash sale — 300 buyers, 3 PS5s — and watch **exactly 3** confirm.

```bash
make up && TOKEN=$(make -s token)

# the load test itself (needs k6 installed, or use the docker image below)
make loadtest TOKEN=$TOKEN
#   -> summary line: confirmed=3 rejected=... throttled=...   (expected: confirmed==3)

# no local k6? run it via docker:
docker run --rm -i --network host -e TOKEN=$TOKEN grafana/k6 run - < shopflow/loadtest/flash-sale.js

# prove the stock hit exactly 0 and never negative:
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U inventory -d inventory -c "select sku, quantity from product where sku='PS5-SLIM';"   # -> 0

# see overselling be IMPOSSIBLE even with raw concurrency (fire 20 parallel reserves for 3 stock):
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8082/api/v1/reservations \
    -H 'Content-Type: application/json' -d "{\"orderRef\":\"r-$i\",\"sku\":\"PS5-SLIM\",\"quantity\":1}" -o /dev/null -w "%{http_code}\n" &
done | sort | uniq -c    # -> only 3 succeed (200/201), the rest 409 insufficient-stock
```

**Proved:** the `count==3` threshold and `quantity=0` (never negative) show the atomic `UPDATE` serialised 300 racing buyers correctly; `429`/`409` are the system correctly shedding load, not errors.

> Break it: temporarily swap `decrementStock` for a read-check-write in Java and re-run — watch `confirmed` jump above 3. That one experiment teaches the whole topic.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — `make loadtest` chalta hai; k6 docker image bhi.

---

## Interview concepts is passage se
- **Lost-update race / read-modify-write** — naive check oversells.
- **Atomic conditional UPDATE + row lock** — check-and-act ek statement mein.
- **Rows-affected as the signal** (`0` = out/unknown) vs reading then branching.
- **Optimistic single-statement vs `SELECT ... FOR UPDATE` vs `@Version`** — kab kaunsa.
- **Rate limiter (per-pod) vs global** — 429, bulkhead/limiter difference.
- **Load test as an executable guarantee** — `count==3`; 409/429 ≠ failure.
- **Cache must not drive stock decisions** — evict, don't trust for writes.

**Interviewer's lens:** kya tu race ko exact interleaving se bol pata hai, aur "DB row lock hi mutual exclusion deta" samajhta hai (app lock nahi). Plus per-pod limiter ki limit aur cache-staleness ka trap.

> Interview line: *"Overselling ek lost-update race hai. Main check aur decrement ek hi SQL mein karta hoon — `update … set quantity = quantity - :qty where quantity >= :qty` — DB row lock concurrent buyers ko serialise karta aur WHERE stock dobara check karta; rows-affected 0 matlab out-of-stock. 300 buyers / 3 units ka k6 test `orders_confirmed == count 3` threshold se ye guarantee CI mein lock karta. Stock decision kabhi cache se nahi; cache sirf read DTO pe, reserve pe evict."*
</content>
