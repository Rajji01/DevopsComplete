# D19 · The query that's 2ms in staging and 4s in prod (same code, same SQL)

> Skill: the data-volume cliff (missing/defeated index → sequential scan). Related mechanism: [`../comprehension/29-silent-data-corruption-heisenbugs/`](../comprehension/29-silent-data-corruption-heisenbugs/) (bug 3), [`../INDEXING-HIBERNATE.md`](../INDEXING-HIBERNATE.md).

---

## Page (symptom)

One endpoint — an order lookup/filter — is **timing out in prod**. p99 on it went from fine to **~4s**, some requests hit the client timeout. The SQL is unchanged, the code is unchanged, there was **no deploy**. The exact same query runs in **~2ms in staging**, so "it works on my machine / in staging" — the dev can't reproduce it at all.

The only thing that's different between staging and prod is **how much data there is**: staging has a few thousand orders; prod has **tens of millions**.

**Before details: identical SQL, instant on small data, catastrophic on large data, no code change. What single thing explains it, and what one command proves it?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- The slow query filters on a column that is **not** in any index (e.g. `where failure_reason = ?`), **or** compares an indexed column against a value of a **different type** (e.g. an indexed `varchar` column bound to a number, forcing an implicit cast).
- `pg_stat_statements` shows this query with a huge **total/mean time** and **rows read ≫ rows returned** (it reads the whole table to return a few).
- DB CPU and disk I/O are high while the query runs; the table is large (tens of millions of rows).
- Staging runs the identical statement in milliseconds — small table, so even a full scan is cheap.
- The indexes that *do* exist (`idx_orders_customer_created`, `idx_orders_status_updated`, `idx_orders_created_at`) are **not** on the filtered column / are **not usable** for this predicate.
- No error, no deploy, no schema change — just growth.
</details>

---

## Your move

1. Same SQL, 2ms vs 4s, only data volume differs. Name the mechanism and why small data **hides** it.
2. What single command turns "mysterious prod timeout" into an obvious diagnosis, and what word are you looking for in its output?
3. Give the two distinct root causes that both produce a sequential scan here, and how you'd tell which one you have.
4. For each cause, the fix — and one way the fix could **still** not be used by the planner.
5. Why do big tables make this a **cliff** (sudden), not a gradual slowdown — and what guard catches it before prod?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Mechanism, why small data hides it (Q1).** With no usable index on the predicate, Postgres must **sequentially scan** the whole table to find matching rows. Cost scales with **row count**: on staging's few-thousand-row table a seq scan is ~2ms (and the planner may even *prefer* it — on tiny tables a scan beats index overhead), so it's **invisible**; on prod's tens-of-millions it's a multi-second full read. The code and SQL are identical — only the **data volume × plan** changed. Small environments **structurally cannot** surface this.

**The one command (Q2).** **`EXPLAIN (ANALYZE, BUFFERS)`** on the query against **prod-scale data/stats**. The word you're hunting is **`Seq Scan`** (sequential scan) on the big table — plus `rows=` in the tens of millions and a large `actual time`. That instantly converts "why is it slow" into "it's reading the whole table because it isn't using an index." (`pg_stat_statements` finds *which* query; `EXPLAIN` explains *why*.)

**Two root causes (Q3).**
- **(a) No index** on the filtered column at all (`failure_reason` was never indexed because nobody filtered on it until now).
- **(b) Index exists but is defeated** — most commonly an **implicit type cast** (the column is `varchar`, the bind is numeric/`bigint`, so Postgres casts the *column* per row and can't use the index), or a function on the column (`lower(col) = ?` with a plain index), or a **leading-column mismatch** on a composite index (filtering on the 2nd column of `(a,b)` without `a`), or **low selectivity** (the value matches most rows, so a scan is genuinely cheaper).
Tell them apart with `EXPLAIN`: (a) shows a seq scan with **no index candidate**; (b) shows the index **exists but isn't chosen** / shows a cast in the plan or a filter that doesn't map to the index — check the predicate types and the composite's leading column.

**Fixes (Q4).**
- (a) **Add an index** on the filtered column (or a composite matching the full predicate + sort, like the existing `(customer_id, created_at desc)`).
- (b) **Fix the type mismatch** (bind the right type / cast the parameter, not the column), index the **expression** (`lower(col)`) if you query it that way, reorder the composite to match, or accept the scan if selectivity is genuinely low (then rethink the query).
- **Still-not-used trap:** even with the index present, the planner won't use it if **statistics are stale** (run `ANALYZE`), if the query is **not selective** enough (it'll prefer a scan), or if a **cast/function** still wraps the column. Adding an index is necessary, not sufficient — verify with `EXPLAIN` that the plan actually **flips to an Index Scan**.

**Why a cliff, and the guard (Q5).** It's sudden because the planner's choice and the scan's cost cross a threshold as the table grows (and as stats update): below some size a seq scan is fine/chosen; above it, the same plan becomes a multi-second table read — the pain **steps**, it doesn't ramp smoothly, so it "suddenly" appears in prod at a data volume staging never reaches. **Guards:** test query plans against **production-scale data** (a perf/staging DB seeded to prod cardinality), add **slow-query logging** + `pg_stat_statements` alerts on mean-time/rows-read, and **review `EXPLAIN` for new query predicates** in code review — catch the `Seq Scan` before 50M rows do.

**Root cause:** a query filters on a column with **no usable index** (missing, or defeated by an implicit cast/expression/low selectivity), so it **sequentially scans** a table that is tiny in staging and enormous in prod — a data-volume cliff, not a code bug.

**Confirm the fix:** `EXPLAIN (ANALYZE)` now shows an **Index Scan** (not Seq Scan), rows-read ≈ rows-returned, query time back to single-digit ms, endpoint p99 recovers, `pg_stat_statements` mean-time drops.
</details>

---

## Reproduce / test (apne haath se)
```bash
make up && TOKEN=$(make -s token)
# see a GOOD plan (indexed) vs a BAD plan (un-indexed) on the SAME table — even with small local data,
# EXPLAIN shows which path each takes (the cliff is cost×rows; the PLAN is visible now):
docker compose -f shopflow/docker-compose.yml exec postgres psql -U orders -d orders -c \
  "explain analyze select * from orders where customer_id='alice' order by created_at desc;"   # Index Scan (idx_orders_customer_created)
docker compose -f shopflow/docker-compose.yml exec postgres psql -U orders -d orders -c \
  "explain analyze select * from orders where failure_reason='x';"                              # Seq Scan (no index) <- the trap
# implicit-cast demo: compare a varchar column to a number and watch the index get skipped:
#   explain select * from orders where order_ref = 12345;   -- type mismatch -> cast -> no index use
# to feel the CLIFF: seed millions of rows into a scratch table and re-run EXPLAIN ANALYZE (time explodes on Seq Scan).
```
**Proved:** the plan — `Index Scan` vs `Seq Scan` — is visible **now**, locally, even though the *timing* only becomes a cliff at prod scale. `EXPLAIN` is the tool that makes a volume-dependent bug diagnosable without prod volume.

## The pattern

- **"Fast in staging, slow in prod, no code change" = a data-volume plan cliff.** Suspect a seq scan from a missing/defeated index before anything else.
- **`EXPLAIN (ANALYZE, BUFFERS)` is the one command.** `Seq Scan` + `rows ≫ returned` is the smoking gun; `pg_stat_statements` finds the culprit query.
- **An index can exist and still not be used** — implicit casts, functions on the column, wrong composite leading column, stale stats, low selectivity. Verify the plan *flips*, don't assume.
- **Small environments can't surface volume bugs.** Test plans at prod cardinality; review `EXPLAIN` for new predicates.
- **The cliff is sudden by nature** — plan cost crosses a threshold with growth, so it "appears" overnight.

> Grounded in: ShopFlow's real indexes (`idx_orders_customer_created (customer_id, created_at desc)`, `idx_orders_status_updated`, `idx_orders_created_at`, `idx_outbox_pending`) and `INDEXING-HIBERNATE.md`. The un-indexed/implicit-cast slow query is a reasoned scenario illustrating the cliff; ShopFlow indexes its *current* hot paths.
