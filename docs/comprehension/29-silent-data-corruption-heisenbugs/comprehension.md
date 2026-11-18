# 29 · Silent wrong-updates & prod Heisenbugs (JPA / index / lock) — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in what ShopFlow does **right** (so the trap is the "if you didn't"): `@Enumerated(EnumType.STRING)` on every enum, `Order` `@Version`, Flyway indexes (`idx_orders_customer_created`, `idx_orders_status_updated`, `idx_outbox_pending`), `OrderService.saveWithEvent` (TransactionTemplate to dodge self-invocation). Bugs marked **[TRAP]** are the "what if it were wrong" — reasoned, not in the repo.
> **Theme:** ye wo bugs hain jahan **koi exception nahi aata** — data chup-chaap galat, ya prod mein dekh ke bhi samajh na aaye. Pairs: [28 concurrency](../28-concurrency-control-locks-jpa-db/), [D19](../../debugging/D19-stale-read-your-writes.md), [D20](../../debugging/D20-index-cliff-seq-scan.md).

---

## Scenario (read cold)

> *A senior review: "The scariest bugs don't throw. Walk me through the ones where the wrong data gets written, or where prod shows you nothing useful." Read once, then answer.*

The errors you can see are the easy ones. The dangerous class is **silent**: the request returns `200`, no exception, no alert — and the data is quietly wrong, or a later read sees something that "can't happen." Five of these live in the JPA / index / lock layer, and ShopFlow avoids each with a specific choice — which is exactly why each choice matters.

**(1) The whole-row overwrite (lost update without a version).** Hibernate, by default, UPDATEs **every column** of an entity, not just the ones you changed (there is no `@DynamicUpdate`). So if two requests both load the same row, request A changes the `email`, request B changes the `phone`, and both `save()`, the second save writes **all** columns from its stale snapshot — silently **reverting** the other's field. No error; a field just "un-saves itself" minutes later. ShopFlow's `Order` is safe because it carries **`@Version`** (the second write fails with a 409 instead of overwriting) **[TRAP]** — remove `@Version` from a concurrently-edited entity and you get exactly this invisible lost update.

**(2) The enum ordinal time-bomb.** ShopFlow maps every status with **`@Enumerated(EnumType.STRING)`** — `'CAPTURED'`, `'REFUNDED'` are stored as text. **[TRAP]** If it were `EnumType.ORDINAL`, statuses would be stored as `0,1,2…`, and the day someone inserts a new value in the **middle** of the enum (`enum Status { CAPTURED, PENDING, FAILED, REFUNDED }`), **every existing row's number now means a different status** — a `FAILED` silently becomes `REFUNDED`. No migration error, no exception; historical data just reinterprets itself. STRING avoids it entirely.

**(3) The index cliff.** ShopFlow indexes its hot query columns (`idx_orders_customer_created (customer_id, created_at desc)`, `idx_orders_status_updated`). **[TRAP]** A query on an **un-indexed** column, or one where an **implicit type cast** defeats the index (e.g. the column is `varchar` and the query binds a number, so Postgres can't use the index), runs a **sequential scan**. In dev with 1,000 rows it's 2ms and invisible; in prod with 50M rows it's a 4-second full scan that only shows up **under data volume you don't have locally** — the textbook "fast in staging, times out in prod," and nothing in the code looks wrong.

**(4) The self-invocation that silently skips the transaction.** `@Transactional` works through a **proxy**; calling an annotated method **from another method in the same class** bypasses the proxy, so it runs **with no transaction at all** — partial writes commit one by one, no rollback. ShopFlow's `OrderService.saveWithEvent` uses a **`TransactionTemplate`** precisely to avoid this (its comment says so). **[TRAP]** The naive version — a public `@Transactional` method called internally — looks transactional, tests may pass when called from outside, and in prod the atomicity silently isn't there.

**(5) The read-your-writes miss on a replica.** The moment you add a **read replica** (topic 26's scaling fix), reads can be served from a copy that lags the primary by milliseconds-to-seconds. A user updates their address (write → primary, `200`), immediately reloads (read → replica), and sees the **old** address — because the replica hasn't caught up. It's **intermittent** (depends on lag at that instant), **can't be reproduced locally** (no replica in dev), and shows **no error** — the hardest kind to debug. (ShopFlow has no replica today, so it's safe by absence — but it's the first thing scaling adds.)

The thread through all five: **absence of an exception is not evidence of correctness.** Each is prevented by a deliberate choice (`@Version`, `STRING`, the right index, `TransactionTemplate`, routing reads that need freshness to the primary), and each is nearly invisible once it ships. The senior skill is **naming how you'd even detect it** — not just the fix.

---

## Questions

### A — Literal
1. By default, how many of an entity's columns does Hibernate write on an update, and what single annotation changes that?
2. Why does `@Enumerated(EnumType.STRING)` prevent a class of silent corruption that `ORDINAL` allows?
3. What is "self-invocation" with `@Transactional`, and what silently happens to the transaction?

### B — Inference
4. Bug (1) (whole-row overwrite) and bug (5) (stale replica read) both produce "my update disappeared," with no error. How would you **tell them apart** from the symptom — what distinguishes a lost **write** from a stale **read**?
5. The index cliff (3) passes every test and every staging check. Why is it structurally invisible until prod, and what single tool turns it from "mysterious timeout" into an obvious diagnosis?

### C — Trade-off
6. `@DynamicUpdate` (write only changed columns) would fix bug (1)'s overwrite *and* reduce write size. Why isn't it just on everywhere — what does it cost, and why is `@Version` often the better guard anyway?
7. Routing "read-your-writes" reads to the **primary** fixes bug (5) but gives up the replica's offload. How do you decide **which** reads must hit the primary and which can tolerate lag?

### D — Transfer / extension
8. A user reports: "I changed my order's shipping note, saw it saved, refreshed, and it was back to the old note — but only sometimes." You have replicas **and** no `@Version` on that entity. Give a plan that distinguishes bug (1) from bug (5) in **production**, using signals you can actually collect.
9. **[TRAP]** A nightly job does `update orders set status='ARCHIVED' where created_at < :cutoff`. In prod it locks for minutes and times out checkout; in staging it's instant. Name the two independent causes (one index-related, one lock/transaction-related) and the fix for each.

### E — Spot-the-flaw
10. To "speed things up," a dev marks a service method `@Transactional(readOnly = true)` and, inside it, calls a repository `save()` to update a `last_seen` timestamp. No error is thrown and the code returns `200`, but the timestamp **sometimes** doesn't change, and there's no pattern. Explain what `readOnly=true` does here, why it's intermittent, and why "no error" made it ship.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **All** columns (the full row), by default — Hibernate generates a static UPDATE of every mapped column. **`@DynamicUpdate`** makes it generate an UPDATE of only the **changed** (dirty) columns.
2. With `STRING`, the enum is stored as its **name** (`'REFUNDED'`), which is stable regardless of enum declaration order. With `ORDINAL`, it's stored as the enum's **position** (`0,1,2…`); inserting or reordering a value **shifts every later position**, so already-stored numbers now map to **different** enum constants — the historical rows silently change meaning. STRING decouples the stored value from the declaration order.
3. Self-invocation = a method calling **another `@Transactional` method on the same bean** directly (`this.foo()`). Spring's transaction advice lives on a **proxy**; an internal `this` call doesn't go through the proxy, so the advice **doesn't run** — the inner method executes with **no transaction** (or the caller's, if any), silently losing atomicity/rollback.
</details>

<details>
<summary>B — Inference</summary>

4. A lost **write** (1) is **permanent** — the data on the primary is actually wrong; read it again a minute later (even from the primary) and the field is still reverted. A stale **read** (5) is **transient** — the primary has the correct value; the user saw old data only because the **replica** hadn't caught up, and a re-read after replication lag passes shows the correct value. **Test:** read the row **directly from the primary** after the fact — if it's wrong → lost write; if it's right but the app showed old → stale replica read. (Also: lost-write correlates with **concurrent edits**; stale-read correlates with **read-immediately-after-write** and replica lag.)
5. It's invisible because the **query plan depends on data volume and statistics** that dev/staging don't have — with few rows the planner may even seq-scan happily (it's fast), so the same SQL is instant in staging and catastrophic in prod; nothing in the *code* differs. The tool: **`EXPLAIN (ANALYZE, BUFFERS)`** on the real prod data/stats — it immediately shows `Seq Scan` (and rows/time), turning "mysterious timeout" into "this query isn't using an index; add one / fix the cast."
</details>

<details>
<summary>C — Trade-off</summary>

6. `@DynamicUpdate` costs Hibernate a **per-update SQL generation** (it can't reuse one cached static statement — it builds the UPDATE from the dirty set each time), which at high write rates is measurable; it also doesn't reuse prepared-statement caching as well. And crucially it **doesn't solve the race** — it narrows the overwrite to overlapping columns, but two requests changing the **same** column still lost-update. **`@Version`** actually detects the conflict (0 rows → 409) regardless of which columns, so it's the correctness guard; `@DynamicUpdate` is a write-size optimization, not a concurrency control. Use `@Version` for correctness; add `@DynamicUpdate` only where wide rows + partial updates justify it.
7. Classify reads by **freshness requirement**: a read the **same user** issues **right after their own write** (read-your-writes — profile just edited, order just placed) must hit the **primary** (or use a "read from primary for N seconds after a write" / sticky routing). Reads that are **aggregate, cross-user, or tolerant of seconds-old data** (catalog browse, dashboards, reports, other people's orders) go to the **replica**. Rule: route by "will a human notice their own change is missing?" — if yes, primary; if it's someone else's or statistical, replica.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Distinguish in prod:**
   - **Read the row from the primary** (bypass the replica) when the report comes in — if the note is **wrong on the primary**, it's a **lost write** (bug 1); if **correct on the primary**, it was a **stale replica read** (bug 5).
   - **Correlate timing/signals:** add structured logs with the `trace.id` for the update and the subsequent read, each tagged with **which DB node served it** (primary vs replica) and the **replica lag** gauge at read time. Stale-read cases will show the read hitting a **lagging replica** moments after the write; lost-write cases will show **two concurrent updates** to the same row id around the same time.
   - **Reproduce the lost write** deterministically: two concurrent updates to different fields of one row without `@Version` → one reverts. **Reproduce the stale read**: write to primary, read from replica within the lag window.
   - **Fixes differ:** lost write → add `@Version` (or `@DynamicUpdate` + same-column guard); stale read → route that read to the primary / read-your-writes. Same symptom, opposite layer — the primary-vs-replica check is the splitter.
9. **[TRAP]** Cause A (**index/scan**): `where created_at < :cutoff` with **no usable index on `created_at`** (or stats making the planner choose a seq scan over millions of rows) → a full-table scan that's instant on staging's small table, minutes on prod's. Fix: an index on `created_at` (ShopFlow actually has `idx_orders_created_at`) and/or **batch** the update (`LIMIT`/keyset) instead of one giant statement. Cause B (**lock/transaction**): one massive `UPDATE` takes **row locks on every matched row in a single long transaction**, blocking concurrent checkout writes to those rows and holding a connection for minutes → checkout timeouts. Fix: **chunk** the archive into small committed batches (e.g. 1,000 rows each, commit between) so locks are short and other writers aren't blocked — plus run it off-peak. (Two independent causes; a big `DELETE`/`UPDATE` needs **both** an index and batching.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `@Transactional(readOnly = true)` tells Hibernate the transaction is **read-only**: it sets the flush mode to **MANUAL/NEVER**, so at commit Hibernate **doesn't flush dirty entities** — the `save()`'s UPDATE may **never be sent** to the DB (and some drivers/DBs also reject writes in a read-only tx). No exception is thrown because, from the app's view, nothing *failed* — the write was simply **not flushed**. It's **intermittent** because whether the change persists can depend on whether something else triggers a flush (a query in the same tx, a different code path), so it "sometimes works." And **"no error" is exactly why it shipped** — the method returns `200`, tests that don't assert the persisted value pass, and the missing write only surfaces as "last_seen is sometimes stale," which looks like a data glitch, not a code bug. Fix: don't write inside a `readOnly` transaction — make the timestamp update its own non-readOnly transactional path (and assert persistence in a test). It's the same lesson as the others: **the absence of an error hid a silent correctness loss.**
</details>

---

## Tod ke dekho — Local (ShopFlow ke sahi choices + trap dekhna)

```bash
# (2) enums are STRING (safe) — stored as text, not ordinals:
grep -rn "EnumType" shopflow/*/src/main/java -r          # all EnumType.STRING
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U payments -d payments -c "select distinct status from payment;"   # 'CAPTURED'/'REFUNDED', not 0/1

# (1) no @DynamicUpdate -> Hibernate writes all columns; Order is guarded by @Version instead:
grep -rn "@DynamicUpdate" shopflow || echo "none -> full-row updates; Order relies on @Version"
grep -n "@Version" shopflow/order-service/src/main/java/com/shopflow/order/order/Order.java

# (4) self-invocation avoided on purpose:
grep -n "TransactionTemplate\|bypasses the proxy" shopflow/order-service/src/main/java/com/shopflow/order/order/OrderService.java

# (3) index cliff — see the plan (local data is small so it's fast; the POINT is EXPLAIN):
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U orders -d orders -c "explain analyze select * from orders where customer_id='alice' order by created_at desc;"   # should use idx_orders_customer_created
docker compose -f shopflow/docker-compose.yml exec postgres \
  psql -U orders -d orders -c "explain analyze select * from orders where failure_reason='x';"   # un-indexed -> Seq Scan (the trap at scale)
```

**Proved:** ShopFlow's choices (`STRING`, `@Version`, deliberate `TransactionTemplate`, indexed hot columns) each close one silent-corruption door; `EXPLAIN` on an un-indexed predicate shows the `Seq Scan` that would be the prod cliff.

## Tod ke dekho — Online
Replica/lag ke liye managed PG with a read-replica → [`ONLINE-LABS.md`](../ONLINE-LABS.md). Bug (5) ko sirf replica-wale setup pe dekha ja sakta (dev single-node mein nahi).

---

## Interview concepts is passage se
- **No exception ≠ correct** — silent data bugs ki poori class.
- **Full-row vs dynamic update**, aur **`@Version`** (asli guard) vs `@DynamicUpdate` (size opt).
- **Enum STRING vs ORDINAL** — ordinal time-bomb.
- **Index cliff / implicit-cast seq scan** — dev fast, prod timeout; **`EXPLAIN (ANALYZE)`**.
- **`@Transactional` self-invocation** (proxy bypass) aur **`readOnly=true`** (no flush) ke silent traps.
- **Read-your-writes on a replica** — intermittent, locally non-reproducible; route fresh reads to primary.
- **Detection-first thinking** — "pata kaise chalega" (primary-vs-replica read, EXPLAIN, trace+node tag).

**Interviewer's lens:** kya tu **bina exception wale** bug ko naam de sakta, lost-**write** vs stale-**read** ko symptom se alag kar sakta, aur **detection method** bata sakta (EXPLAIN, primary pe re-read, node-tagged traces) — sirf fix nahi.

> Interview line: *"Darane wale bugs woh hain jo throw nahi karte. Hibernate default poori row UPDATE karta (no `@DynamicUpdate`), to bina `@Version` ke do concurrent partial-edits ek-dusre ko silently revert kar dete — Order `@Version` se bacha hai. Enums `STRING` hain, warna `ORDINAL` mein beech mein value daalo to purani rows ka matlab badal jaata. Index cliff: un-indexed/implicit-cast query dev (1k rows) pe 2ms, prod (50M) pe seq-scan 4s — `EXPLAIN ANALYZE` hi pakadta. `@Transactional` self-invocation proxy bypass karke chup-chaap bina txn chalta (isliye `OrderService` TransactionTemplate use karta), aur `readOnly=true` mein save flush hi nahi hota. Aur replica aate hi read-your-writes: user apna update turant padhe to replica lag se purana dikhta — intermittent, locally repro nahi. Senior skill: 'detect kaise karun' — primary pe re-read (lost-write vs stale-read), EXPLAIN, node-tagged traces."*
