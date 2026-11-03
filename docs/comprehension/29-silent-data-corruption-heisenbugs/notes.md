# 29 · Silent data corruption / prod Heisenbugs — NOTES

> Pair: [`comprehension.md`](comprehension.md). Theme: **exception nahi aata, data chup-chaap galat / prod mein dekh ke bhi samajh na aaye.** ShopFlow har trap ko ek **deliberate choice** se band karta — wahi yaad rakho.

---

## KYA (one breath)
Sabse khatarnak bugs **throw nahi karte**: `200` aata, data galat ya baad ki read "impossible" dikhati. Paanch JPA/index/lock/replica traps — har ek ko ShopFlow ne sahi choice se roka hai, aur senior skill = **"detect kaise karun"**, sirf fix nahi.

## EASY WAY
> **"No error ≠ correct. Lost **write** = primary pe bhi galat (permanent). Stale **read** = primary sahi, replica purana (transient). Index cliff = dev fast, prod timeout → `EXPLAIN` hi sach bolta."**

## THE 5 SILENT BUGS (+ ShopFlow ka guard + detection)
| # | Silent bug | Kaise galat hota | ShopFlow guard | **Detect kaise** |
|---|-----------|------------------|----------------|------------------|
| 1 | **Whole-row overwrite** (lost update) | Hibernate saari columns UPDATE karta (no `@DynamicUpdate`); 2 partial-edits ek-dusre ko revert | `Order` **`@Version`** → loser 409 | primary pe re-read galat; 2 concurrent updates same id |
| 2 | **Enum ORDINAL bomb** | `0,1,2` store; beech mein value daalo → purani rows ka matlab badla | **`@Enumerated(STRING)`** sab jagah | DB mein value text hai ya number? |
| 3 | **Index cliff** | un-indexed / implicit-cast → seq scan; dev 2ms, prod 4s | hot columns indexed (`idx_orders_customer_created`...) | **`EXPLAIN (ANALYZE,BUFFERS)`** → Seq Scan |
| 4 | **`@Transactional` self-invocation** | same-class call proxy bypass → **koi txn nahi**, partial commit | `OrderService` **`TransactionTemplate`** (jaan-bujh ke) | call internal hai? proxy miss? no rollback on failure |
| 5 | **Stale read-your-writes** (replica) | write→primary, read→lagging replica → purana | koi replica nahi (abhi) → safe-by-absence | read ne **kaunsा node** serve kiya + replica-lag gauge |

## DIAGRAM — lost WRITE vs stale READ (same symptom "update gायब")
```
 LOST WRITE (bug 1):                         STALE READ (bug 5):
  A load row(v) \                             write email ──► PRIMARY (committed, correct)
  B load row(v)  } both snapshot v            read  email ──► REPLICA (lag) ──► OLD value
  A save email=x (all cols)                   (primary is RIGHT; replica catches up later)
  B save phone=y (all cols, email=STALE)
  => email reverted ON PRIMARY (permanent)    => re-read after lag = correct (transient)
  SPLIT TEST: read the row straight from the PRIMARY.
     wrong  -> lost write (add @Version)
     right  -> stale read (route fresh read to primary)
```

## DIAGRAM — index cliff (kyun dev pe invisible)
```
 staging: orders=1,000 rows   query WHERE failure_reason='x'
    planner: Seq Scan 1k = ~2ms  (fast, nobody notices)
 prod: orders=50,000,000 rows  SAME query, SAME code
    planner: Seq Scan 50M = ~4s  -> checkout timeout
 EXPLAIN (ANALYZE, BUFFERS):  "Seq Scan on orders ... rows=50000000"
    -> add index / fix implicit cast (varchar col vs numeric bind)
 NOTHING in the code changed — only the data volume.
```

## KYU (har guard ka reason)
- **`@Version`** (not `@DynamicUpdate`): dynamic-update sirf overlap kam karta, **same-column race abhi bhi lost**; version 0-rows se **detect** karta (409). Correctness guard = version.
- **`STRING`**: stored value declaration-order se **decouple**. Ordinal = position, reorder = silent reinterpret.
- **Indexes**: planner data-volume+stats pe plan chunta; chhoti table pe seq-scan bhi fast → trap chhupta. Prod stats pe `EXPLAIN` sach.
- **TransactionTemplate**: `@Transactional` **proxy** pe hai; `this.method()` proxy miss → advice nahi chalta → no txn. ShopFlow ne isliye template use kiya.
- **`readOnly=true`**: flush-mode MANUAL → dirty entity **flush hi nahi** hota → save silently gayab (intermittent, kyunki koi aur query flush trigger kar sakti).

## DETECTION TOOLBOX (senior move — "pata kaise chalega")
| Suspected | Tool / signal |
|-----------|---------------|
| lost write vs stale read | **primary pe seedha re-read** (splitter) |
| index cliff | `EXPLAIN (ANALYZE, BUFFERS)` prod stats pe; `pg_stat_statements` top slow |
| missing flush / txn | assert persisted value in test; SQL log (`org.hibernate.SQL`); check no UPDATE emitted |
| replica lag | `pg_stat_replication` / lag gauge; node-tagged trace (read served by?) |
| ordinal risk | DB column values text vs int |
| which txn/proxy | AOP proxy check; is call internal `this.`? |

## GOTCHAS (tagde, interview pakadta)
- **No exception ≠ correct** — silent class.
- **Hibernate default = full-row UPDATE** (no `@DynamicUpdate`).
- **`@Version` detect karta, `@DynamicUpdate` sirf size** — alag kaam.
- **`ORDINAL` kabhi mat** (ya `@Enumerated` chhodo = ORDINAL default!) — hamesha `STRING`.
- **Self-invocation = no txn**; `readOnly=true` + save = **no flush**.
- **Big UPDATE/DELETE = index + batching dono** (warna lock-storm + seq-scan).
- **Replica = read-your-writes** turant; fresh read primary pe.

## UNDERSTANDING CHECK (khud bol)
1. "Mera update gayab, kabhi-kabhi" — pehla test? → *primary pe seedha re-read: galat=lost write, sahi=stale read.*
2. Dev fast, prod timeout, same SQL — pehla command? → *`EXPLAIN (ANALYZE, BUFFERS)` → Seq Scan dekho.*
3. `@Enumerated` likhna bhool gaye to default kya? → *ORDINAL (khatarnak) — hamesha STRING likho.*
4. `@Transactional` method same class se call — txn mila? → *nahi, proxy bypass, koi txn nahi.*
5. `readOnly=true` ke andar save() — kya hota? → *flush nahi hota, write silently gayab (intermittent).*
6. Nightly `update ... where created_at<cutoff` prod pe timeout — 2 causes? → *index missing (seq scan) + ek bada txn ka lock-storm; fix index + batching.*

## INTERVIEW LINE
> *"Darane wale bugs throw nahi karte. Hibernate default poori row UPDATE karta, to bina `@Version` do partial-edits silently revert — Order version se safe. Enums `STRING` (ORDINAL hota to beech mein value daalne pe purani rows ka matlab badal jaata). Index cliff: un-indexed/implicit-cast query dev pe 2ms, prod 50M rows pe seq-scan 4s — `EXPLAIN ANALYZE` hi pakadta. `@Transactional` self-invocation proxy bypass = no txn (isliye TransactionTemplate), `readOnly=true` mein save flush nahi hota. Replica aate hi read-your-writes stale — intermittent, locally repro nahi. Detection: primary pe re-read (write-loss vs stale-read), EXPLAIN, node-tagged traces."*

---
**Grounded:** `Payment/Order/Reservation` `@Enumerated(STRING)`, `Order.java` `@Version`, no `@DynamicUpdate` anywhere, `OrderService.saveWithEvent` (TransactionTemplate + "bypasses the proxy" comment), Flyway indexes (`idx_orders_customer_created`, `idx_orders_status_updated`, `idx_orders_created_at`, `idx_outbox_pending`). `[TRAP]` cases reasoned, repo mein nahi.
