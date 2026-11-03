# 28 · Millions of requests, one row: locks, no-locks, DB vs JPA — which API gets which — comprehension

> Companion notes: [`notes.md`](notes.md) (full decision matrix + JPA↔DB translation + diagrams).
> Grounded in ShopFlow's **three real** concurrency mechanisms: `Order.java` (`@Version` optimistic), `inventory/ProductRepository.decrementStock` (`@Modifying` atomic no-lock), `shopflow-outbox/OutboxRepository.findUnpublished` (`@Lock(PESSIMISTIC_WRITE)` + lock-timeout `-2` → `FOR UPDATE SKIP LOCKED`). Cases **not** in the repo are marked **[NEW]** — reasoned, not claimed.
> **Capstone trilogy:** [25 correctness](../25-correctness-model-capstone/) · [26 scaling](../26-scaling-100x-bottlenecks/) · [27 observability](../27-observability-sli-incident-model/). Ye topic 26's "hot row" ko **poora concurrency-control ke tareeqon** mein kholta.

---

## Scenario (read cold)

> *A design review: "At millions of requests, different endpoints hit the same rows in different ways. For each, pick the concurrency control — and show what it becomes at the database." Read once, then answer.*

When many requests touch the **same data** at once, you must stop two of them from stepping on each other (the **lost update**: both read, both write, one write vanishes). There are three families of answer, and ShopFlow already uses **all three** — each on a different endpoint, for a reason.

**(1) No lock you hold — an atomic conditional write.** Inventory's stock decrement is one statement: `update product set quantity = quantity - :qty where sku = :sku and quantity >= :qty` (a JPA `@Modifying` query). You **never read-then-write**; the check and the change are the *same* statement. Postgres takes a **row lock only for the duration of that `UPDATE`** and the `where quantity >= :qty` re-checks inside it. The method returns **rows-affected** — `1` = done, `0` = out of stock. No `@Version`, no `SELECT ... FOR UPDATE`. This is the best fit for a **single-row, ultra-high-contention counter** (a flash sale on one SKU): the lock is held for microseconds and there's no app-side retry.

**(2) Optimistic — assume no conflict, detect it, let the loser fail.** The `Order` entity has an `@Version` column. When you load an order, change its status, and save, Hibernate emits `update orders set status=?, version=3 where id=? and version=2`. If someone else already moved it to version 3, **zero rows match**, Hibernate throws `OptimisticLockException`, and ShopFlow's handler maps it to **409**. No lock is held between read and write — you only pay if there's an actual collision. This fits **low-contention, read-modify-write on a whole entity** (cancel an order twice at once → one wins, one gets 409), where locking every read would be wasteful.

**(3) Pessimistic — take the lock up front, but skip the busy rows.** The outbox relay must let **several pods** drain the same table without publishing a row twice. Its repository uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` with a lock-timeout hint of **`-2`**, which Hibernate renders as **`SELECT ... FOR UPDATE SKIP LOCKED`**. Each relay pod grabs a batch of **unlocked** rows and **skips** rows another pod already holds — so workers don't block each other, they divide the work. This fits a **work queue / job-claiming** pattern (many workers, each row processed once).

The database is where it all actually happens, and the **isolation level** sets the rules. Postgres defaults to **READ COMMITTED**: each statement sees a fresh snapshot, which is why the atomic `UPDATE` and `@Version` both work (the re-check / version-check happens against committed data at write time). Two anomalies to name: a plain **read-modify-write across two statements** can **lose an update** under READ COMMITTED (that's exactly why stock uses one atomic statement, not a read then a write); and a **write-skew** (two txns each read a set, each writes based on it, together they break an invariant) is **not** caught by READ COMMITTED or even REPEATABLE READ — only **SERIALIZABLE** (Postgres SSI) catches it, at the cost of serialization failures you must retry. ShopFlow needs none of SERIALIZABLE today because its invariants are **single-row** (stock on one row, order state on one row) — the moment an invariant spans rows, isolation level becomes a design choice.

What you **can't** do once the contention is cross-service: there is no database lock that spans order-service's DB and payment-service's DB. That's why cross-service correctness is **not** a lock at all — it's the saga + idempotency of topic 25. Locks are a **single-database** tool.

---

## Questions

### A — Literal
1. Name ShopFlow's three concurrency mechanisms and the one endpoint/table each is used on.
2. For the stock decrement, what exact SQL runs, what does "rows affected = 0" mean, and how long is a lock held?
3. What does `@Lock(PESSIMISTIC_WRITE)` + lock-timeout `-2` render as on Postgres, and what problem does the "SKIP LOCKED" part solve?

### B — Inference
4. The order-cancel path uses **optimistic** `@Version`, but the stock path uses a **no-lock atomic UPDATE** instead of `@Version`. Both prevent lost updates — why is each the *right* choice for its endpoint and *wrong* for the other?
5. Under Postgres default **READ COMMITTED**, a naive "read quantity, check in Java, write quantity-1" can still oversell. Explain the exact interleaving, and which of the three mechanisms makes it impossible.

### C — Trade-off
6. Optimistic (`@Version` + retry) vs pessimistic (`SELECT ... FOR UPDATE`) for the *same* contended row. Describe the load profile where each wins, and the pathological behavior each has under the other's bad case.
7. The atomic conditional `UPDATE` is the fastest for a single-row counter. Why not use it for *everything* — what kinds of updates can't be expressed as one conditional statement and need optimistic/pessimistic instead?

### D — Transfer / extension
8. **[NEW]** A wallet endpoint `debit(userId, amount)` must never go negative, under millions of concurrent debits on **hot** accounts. Give the DB-level design and the JPA code shape, and say which of the three families it is and why. Then: a `transfer(from, to, amount)` touches **two** rows — what new hazard appears, and what are the two standard fixes?
9. **[NEW]** A reporting job must read 10,000 orders, compute a total, and not let the total be corrupted by concurrent writes — but it must not block checkout. Which isolation level / read mode fits (snapshot vs locking read), and why is taking `FOR UPDATE` on 10,000 rows the wrong answer?

### E — Spot-the-flaw
10. To "make orders safe," a dev wraps the cancel flow in a **pessimistic** `SELECT ... FOR UPDATE` on the order row **and holds it while calling payment-service** to refund (a 1–2s remote call), inside one `@Transactional`. Functionally it works in test. Under millions of requests it causes an outage that has **nothing to do with wrong data**. Explain the exact failure, and which ShopFlow rule (from topics 04/26) it breaks.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **(1) No-lock atomic `@Modifying` UPDATE** — `inventory ProductRepository.decrementStock` on the **product** row (stock). **(2) Optimistic `@Version`** — the **orders** table (order state transitions / cancel). **(3) Pessimistic `FOR UPDATE SKIP LOCKED`** — `shopflow-outbox OutboxRepository.findUnpublished` on the **outbox_event** table (relay job-claiming).
2. `update product set quantity = quantity - :qty where sku = :sku and quantity >= :qty`. **Rows affected = 0** means **either the SKU doesn't exist or there wasn't enough stock** (the `>=` guard failed) — nothing was changed. The row lock is held **only for the duration of that single `UPDATE` statement** (microseconds), then released at statement/transaction end.
3. It renders as **`SELECT ... FOR UPDATE SKIP LOCKED`**. `FOR UPDATE` locks the selected rows; **`SKIP LOCKED`** makes a second worker **skip** rows already locked by another instead of **blocking** on them — so multiple relay pods **partition** the unpublished rows and drain them in parallel without double-publishing, instead of serializing behind each other.
</details>

<details>
<summary>B — Inference</summary>

4. **Stock = single column, extreme contention, guard expressible in SQL (`quantity >= qty`).** The atomic `UPDATE` resolves check-and-decrement in **one** locked statement — no read round-trip, no retry loop, lock held for microseconds; under 300k buyers that's essential. `@Version` here would be **terrible**: every losing buyer gets `OptimisticLockException` and **retries**, and under heavy contention almost everyone loses and retries — a retry storm (livelock-ish thrash). **Order cancel = whole-entity read-modify-write, low contention.** `@Version` is perfect: no lock held on the common no-conflict path, and the rare double-cancel cleanly yields one 409. An atomic conditional `UPDATE` is **wrong** for cancel because the change isn't a single arithmetic guard on one column — it's "load entity, apply business rules (is it cancellable? what's the next state?), persist" — logic that doesn't compress into one `WHERE`-guarded statement.
5. **Interleaving:** T1 `SELECT quantity` → 3; T2 `SELECT quantity` → 3 (both see committed 3 under READ COMMITTED); T1 checks `3>=1` ok, writes `quantity=2`; T2 checks `3>=1` (from its stale read) ok, writes `quantity=2`. Two units sold, one decrement lost — a **lost update**, because the read and the write are **separate statements** and READ COMMITTED gives each `SELECT` a snapshot that doesn't block the other. The **atomic conditional `UPDATE`** makes it impossible: the check lives *inside* the write, which takes a **row lock**, so T2's `UPDATE` waits for T1's to commit, then re-evaluates `quantity >= 1` against the **new** value (2→1), never double-spending. (`@Version` would also catch it — as a version mismatch → retry — but at higher cost here.)
</details>

<details>
<summary>C — Trade-off</summary>

6. **Optimistic wins under LOW contention** (conflicts rare): no lock held, no blocking, you only pay (a retry) on the occasional real collision — great throughput for "many rows, each rarely contended" (orders, profiles). Its **pathology under HIGH contention**: everyone collides, everyone retries, you get a **retry storm** — work is wasted and latency balloons. **Pessimistic wins under HIGH contention on a specific row** (conflicts common, work per holder short): take the lock, do the work, release — contenders queue in an orderly line, no wasted retries. Its **pathology under LOW contention / long hold**: you **serialize access you didn't need to** and, if you hold the lock across slow work, you create a **convoy / deadlock risk** and tank throughput. Rule of thumb: rare conflict → optimistic; frequent conflict on a hot row with a short critical section → pessimistic; single-column arithmetic → neither, use the atomic `UPDATE`.
7. The atomic conditional `UPDATE` only works when the entire decision is **one predicate + one arithmetic/assignment on columns the DB can evaluate** (`quantity >= qty` → `quantity - qty`). It **can't** express: multi-step business logic that needs the loaded entity (state machines, validation across fields), updates spanning **multiple rows/tables** that must be consistent together, or "read value, call a service, then write." Those need to **materialize the entity** (so you're back to read-modify-write) — guarded by `@Version` (optimistic) or `FOR UPDATE` (pessimistic). The atomic `UPDATE` is a scalpel for single-row guarded arithmetic, not a general tool.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **[NEW] `debit`:** it's the **atomic-UPDATE (no-lock) family**, same shape as stock — the invariant "never negative" is a single-row guard: `update wallet set balance = balance - :amt where user_id = :id and balance >= :amt`; **rows-affected 0 = insufficient funds** (return 402/409). JPA: a `@Modifying @Query` method returning `int`. Best for hot accounts because the lock is held for microseconds and there's no retry. (If you also need an append-only ledger entry, do the `UPDATE` **and** the ledger `INSERT` in **one transaction**.) **`transfer(from,to,amount)` touches two rows → new hazard: deadlock.** Two concurrent transfers A→B and B→A can each lock one row and wait for the other → **deadlock**. Two standard fixes: **(a) lock the rows in a consistent global order** (always lock the lower account id first) so a cycle can't form; **(b)** keep each leg a single atomic `UPDATE` and let the DB detect/rollback the deadlock while you **retry** — plus do both legs in one transaction so money is never half-moved. (At cross-service scale this becomes a saga, topic 25.)
9. **[NEW] Reporting read:** use a **snapshot read**, not a locking read — in Postgres, run the job in a **REPEATABLE READ** (or SERIALIZABLE) transaction so it sees one **consistent snapshot** of all 10,000 orders as of its start, via **MVCC**, **without taking any locks** and **without blocking checkout** (writers create new row versions; the reader keeps reading its snapshot). `FOR UPDATE` on 10,000 rows is wrong because it would **lock every one of them**, blocking all concurrent checkout writes to those orders for the whole report — turning a read into a massive write-blocker (and risking lock-table pressure/deadlock). The whole point of MVCC is that **readers don't block writers and writers don't block readers**; a reporting read should use that, not locks.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The `SELECT ... FOR UPDATE` holds a **row lock on the order for the entire 1–2s payment-service call**, inside the transaction — and that transaction also holds a **database connection** from the pool the whole time. Under millions of requests: every concurrent cancel of a contended order **queues behind that held lock** (and even distinct orders pile up because **connections are pinned** for 1–2s each). The `DB_POOL_SIZE 5`/pod pool drains, new requests wait for a connection, latency explodes, and you get the **connection-exhaustion / pool-starvation outage** of D16/topic 26 — *not* a data-correctness bug; the data is fine, the system is just **holding scarce resources across a slow remote call**. It breaks ShopFlow's core rule from **topic 04**: **never hold a transaction (or a lock, or a connection) around a remote call** — do the remote call *outside* any DB transaction, persist the result in a short separate transaction, and make it idempotent/reconcilable. The correct cancel design keeps the DB work to short transactions and drives the refund via the **saga** (progress-as-state + reconciler), holding **no** lock across the network.
</details>

---

## Tod ke dekho — Local (teeno mechanisms ko code mein dekho)

```bash
# (1) no-lock atomic UPDATE (stock) — the single guarded statement:
grep -n "update Product" shopflow/inventory-service/src/main/java/com/shopflow/inventory/product/ProductRepository.java

# (2) optimistic @Version (orders) — and where the loser becomes 409:
grep -n "@Version" shopflow/order-service/src/main/java/com/shopflow/order/order/Order.java
grep -n "OptimisticLock\|version" shopflow/order-service/src/main/java/com/shopflow/order/common/GlobalExceptionHandler.java

# (3) pessimistic FOR UPDATE SKIP LOCKED (relay) — @Lock + timeout -2:
grep -n "PESSIMISTIC_WRITE\|lock.timeout\|-2\|publishedAt is null" shopflow/shopflow-outbox/src/main/java/com/shopflow/outbox/OutboxRepository.java

# see the ACTUAL SQL Hibernate emits (turn on SQL logging) and the lock clause:
grep -rn "show-sql\|format_sql\|org.hibernate.SQL" shopflow/*/src/main/resources/application.yml

# prove optimistic conflict live: two concurrent cancels of ONE order -> one 200, one 409
make up && TOKEN=$(make -s token)
ID=$(curl -s -X POST localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' | jq -r .id)
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" &
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" &
wait   # -> one 2xx, one 409 (version mismatch) under a real race; sequential just gives 2xx then 409/409

# prove the atomic decrement never oversells: topic 05's 20-parallel-reserve demo (3x201, 17x409).
```

**Proved:** the three families are all real in the repo — atomic `UPDATE` (stock), `@Version` (orders → 409), `FOR UPDATE SKIP LOCKED` (relay). The `[NEW]` cases (wallet/transfer/reporting isolation) are reasoned extensions, not in the code.

## Tod ke dekho — Online
Stack chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md). Isolation/locking experiments psql ke do sessions se bhi: `BEGIN; UPDATE ... ;` ek session mein (commit mat karo) → doosre session mein wahi row `UPDATE` karke blocking dekho; phir `FOR UPDATE SKIP LOCKED` se skip dekho.

---

## Interview concepts is passage se
- **Lost update** aur teen ilaj: atomic-conditional-UPDATE · optimistic `@Version` · pessimistic `FOR UPDATE`.
- **JPA ↔ DB translation** — `@Version`→`WHERE version=?`, `@Lock(PESSIMISTIC_WRITE)`→`FOR UPDATE`, timeout `-2`→`SKIP LOCKED`, `@Modifying`→raw `UPDATE`.
- **Contention profile picks the tool** — rare conflict→optimistic (retry storm agar galat), hot row short critical section→pessimistic (convoy/deadlock agar lamba hold), single-column arithmetic→atomic UPDATE.
- **Isolation levels** — READ COMMITTED (default, lost-update across 2 statements possible), REPEATABLE READ (snapshot), SERIALIZABLE/SSI (write-skew catches, retry cost).
- **MVCC** — readers don't block writers; reporting = snapshot read, not `FOR UPDATE` on 10k rows.
- **Two-row ops → deadlock** — consistent lock ordering ya detect-and-retry.
- **Locks are single-DB** — cross-service = saga + idempotency (25), kabhi lock-across-network nahi (04/26).

**Interviewer's lens:** kya tu **per-case** sahi tool chun sakta (aur galat tool ka pathology bata sakta), JPA annotation ko **asli SQL + lock** mein translate kar sakta, aur jaanta ki **lock ko remote call ke around kabhi mat pakdo** (04/26 ka D16 outage).

> Interview line: *"Lost update rokne ke teen tareeqe, aur mere project mein teeno hain: stock pe **no-lock atomic conditional UPDATE** (`quantity>=:qty`, rows-affected signal, lock sirf statement bhar — hot SKU ke liye best); order state pe **optimistic `@Version`** (Hibernate `WHERE version=?`, loser ko 409 — low contention, koi lock nahi hold hota); relay pe **pessimistic `FOR UPDATE SKIP LOCKED`** (`@Lock(PESSIMISTIC_WRITE)`+timeout `-2`, multi-pod job-claim bina double-publish). Tool contention-profile se chunta: rare→optimistic (warna retry storm), hot-row short→pessimistic (warna convoy/deadlock), single-column→atomic. Isolation default READ COMMITTED — isliye stock ek hi atomic statement mein, do statement mein lost-update ho jaata; write-skew chahiye to SERIALIZABLE (SSI, retry cost); reporting ke liye MVCC snapshot read, 10k rows pe `FOR UPDATE` nahi. Aur lock kabhi remote call ke around nahi — warna pool-starvation outage (mere D16 wala)."*
