# 28 · Concurrency control (locks / no-locks / JPA↔DB) — NOTES

> Pair: [`comprehension.md`](comprehension.md). Topic 26's "hot row" ka poora tareeqa. ShopFlow mein **teeno** families real hain; `[NEW]` = reasoned, repo mein nahi.

---

## KYA (one breath)
Jab millions ek hi row pe aate hain → **lost update** rokna hai. Teen families: **(1) no-lock atomic conditional UPDATE**, **(2) optimistic `@Version`**, **(3) pessimistic `FOR UPDATE` (+SKIP LOCKED)**. Tool **contention-profile** se chunta. JPA annotation asli mein ek **SQL + lock** banta — wahi samajhna.

## EASY WAY (ek line)
> **"Rare conflict → optimistic (sasta, par bahut takkar pe retry-storm). Hot row, chhota kaam → pessimistic (line lag jaati, par lamba hold = convoy/deadlock). Ek column ka arithmetic → koi lock nahi, atomic UPDATE."**

## 3 FAMILIES — ShopFlow mein real
| Family | ShopFlow jagah | JPA code | Lock held? | Best case |
|--------|----------------|----------|-----------|-----------|
| **No-lock atomic** | stock `decrementStock` | `@Modifying @Query("update ... where quantity>=:qty")`, returns `int` | sirf statement bhar (µs) | **single-column guard, extreme contention** (flash sale) |
| **Optimistic** | `Order` state/cancel | `@Version long version` | **nahi** (read–write ke beech) | **whole-entity RMW, low contention** (loser→409) |
| **Pessimistic + skip** | outbox relay | `@Lock(PESSIMISTIC_WRITE)` + hint `lock.timeout=-2` | row lock jab tak txn | **work-queue / job-claim** (multi-worker) |

## JPA ↔ DB TRANSLATION (sabse important — "kaise interact karte")
| JPA / Hibernate | Postgres SQL banta | Lock | Fail mode |
|------------------|--------------------|------|-----------|
| `@Version` + `save()` | `UPDATE ... SET version=n+1 WHERE id=? AND version=n` | koi nahi (write-time check) | 0 rows → `OptimisticLockException` → **409** |
| `@Lock(PESSIMISTIC_WRITE)` | `SELECT ... FOR UPDATE` | row lock (blocks others) | wait / `lock_timeout` → exception |
| `@Lock(PESSIMISTIC_WRITE)` + `lock.timeout=-2` | `SELECT ... FOR UPDATE **SKIP LOCKED**` | row lock, but skips busy | locked rows **skip** (no block) |
| `@Lock(PESSIMISTIC_READ)` | `SELECT ... FOR SHARE` | shared (reads ok, writes wait) | — |
| `@Modifying @Query("update...")` | raw `UPDATE ... WHERE guard` | row lock for statement | returns **rows-affected** (0/1) |
| plain `findById` + setter + save | `SELECT` snapshot, phir `UPDATE` | koi nahi | **lost update** possible (RMW gap) |
| `@Transactional(isolation=SERIALIZABLE)` | `SET TRANSACTION ISOLATION SERIALIZABLE` | SSI predicate tracking | serialization failure → **retry** |

## DECISION TREE (kaunsा tool)
```
 ek row, ek column ka guard+arithmetic? (stock, balance)
   └─ YES → atomic conditional UPDATE  (no lock, rows-affected signal)  ← fastest under contention
   └─ NO  → whole-entity read-modify-write chahiye?
             ├─ conflict RARE  → OPTIMISTIC @Version (+ retry on 409 if needed)
             └─ conflict FREQUENT on a hot row, critical section SHORT → PESSIMISTIC FOR UPDATE
                   └─ many workers draining a queue? → + SKIP LOCKED
 invariant spans MULTIPLE rows? → lock-order OR SERIALIZABLE(SSI)+retry
 read-only big scan? → MVCC snapshot (REPEATABLE READ), NOT FOR UPDATE
 contention is CROSS-SERVICE? → no DB lock possible → SAGA + idempotency (topic 25)
```

## PER-CASE (user ne yahi poocha — "different API, alag tarike")
| Endpoint (case) | Shape | Family | Why | In repo? |
|-----------------|-------|--------|-----|----------|
| `POST /orders` stock decrement | 1 col, 300k buyers/1 SKU | **atomic UPDATE** | µs lock, no retry storm | ✅ |
| `DELETE /orders/{id}` cancel | entity RMW, low contention | **optimistic @Version** | no lock on happy path, loser→409 | ✅ |
| outbox relay drain | queue, many pods | **pessimistic SKIP LOCKED** | partition rows, once-each | ✅ |
| `POST /wallet/debit` **[NEW]** | 1 col guard `balance>=amt` | **atomic UPDATE** | same as stock; rows=0 → insufficient | ✖ reasoned |
| `POST /transfer` **[NEW]** | 2 rows | atomic×2 in 1 txn + **lock-order / retry** | deadlock hazard (A→B vs B→A) | ✖ reasoned |
| reporting sum 10k orders **[NEW]** | big read | **MVCC snapshot** (REPEATABLE READ) | readers≠block writers; FOR UPDATE on 10k = outage | ✖ reasoned |
| cross-service order↔payment | 2 DBs | **saga + idempotency** | no lock across network | ✅ (25) |

## ISOLATION LEVELS (DB-level — "db level pr hoga to diff ways")
| Level | Postgres default? | Rokta | Nahi rokta | Kab use |
|-------|-------------------|-------|-----------|---------|
| **READ COMMITTED** | ✅ default | dirty read | **lost update** (2-statement RMW), write-skew, phantom | normal; stock atomic-UPDATE isi pe safe (check inside write) |
| **REPEATABLE READ** | — | non-repeatable read, phantom (PG snapshot) | write-skew | **consistent snapshot read** (reporting) |
| **SERIALIZABLE (SSI)** | — | **sab** incl. write-skew | — (serialization failure deta → retry) | cross-row invariant, warna galat | 
- **Lost update** (READ COMMITTED): T1,T2 dono `SELECT`→3, dono `UPDATE`→2. Fix: atomic `UPDATE ... WHERE quantity>=qty` (check write ke andar, row lock serialize karta).
- **Write-skew**: dono alag rows padhte, invariant todte (e.g. "kam se kam 1 doctor on-call" — dono last doctor chhodh dete). Sirf **SERIALIZABLE** pakadta.

## DIAGRAM — optimistic vs pessimistic vs atomic (ek hot row)
```
 ATOMIC UPDATE (stock):
   T1: UPDATE ... WHERE qty>=1  ─[row lock µs]─► qty 3→2, rows=1
   T2: UPDATE ... (waits µs) ───► re-check qty>=1 on 2, 2→1, rows=1   (never oversell)

 OPTIMISTIC (@Version, cancel):
   T1 read v2 ... T2 read v2 ... T1 UPDATE WHERE v=2 ✓ (v→3)
                                 T2 UPDATE WHERE v=2 ✗ 0 rows → 409   (no lock held)

 PESSIMISTIC (FOR UPDATE):
   T1 SELECT..FOR UPDATE [LOCK]───do work───COMMIT(release)
   T2 SELECT..FOR UPDATE ....(BLOCKS on T1).... then proceeds
   + SKIP LOCKED: T2 skips T1's rows, grabs others (no block) ← relay
```

## GOTCHAS (jo interview pakadta)
- **Lock + remote call = outage.** `FOR UPDATE` ya txn ko network call ke around mat pakdo → connection/lock held 1-2s → pool starve → D16/topic-26 outage. (Topic 04 rule.)
- **Optimistic under high contention = retry storm.** Hot SKU pe `@Version` mat lagao.
- **Pessimistic long hold = convoy/deadlock.** Critical section chhota rakho; 2 rows → consistent order.
- **`@Modifying` + stale persistence context:** `clearAutomatically=true/flushAutomatically=true` zaroori (warna entity cache purana) — ShopFlow stock query mein hai.
- **MVCC:** readers writers ko block nahi karte — bada read `FOR UPDATE` se mat karo.
- **Locks single-DB.** Cross-service → saga, distributed-lock (Redis/Redlock) bhi fragile (clock/failover) — prefer idempotency.

## ALTERNATE (aur tareeqe, kab)
| Need | ShopFlow | Alternate | Kab |
|------|----------|-----------|-----|
| hot single-row | atomic UPDATE | `SELECT FOR UPDATE` | jab multi-step logic chahiye |
| entity RMW | optimistic | pessimistic | conflict frequent ho |
| queue claim | FOR UPDATE SKIP LOCKED | Kafka/SQS (broker owns it) | true messaging chahiye |
| cross-row invariant | (none today) | SERIALIZABLE+retry / lock-order | invariant spans rows |
| cross-service mutex | saga+idempotency | Redis distributed lock | best avoid; lock-free prefer |

## UNDERSTANDING CHECK (khud bol)
1. Stock pe `@Version` kyun nahi? → *extreme contention; har loser retry → retry storm. Atomic UPDATE µs lock.*
2. `@Lock(PESSIMISTIC_WRITE)` + timeout `-2` = kya SQL? → *`FOR UPDATE SKIP LOCKED`.*
3. READ COMMITTED pe naive stock oversell kaise? → *T1,T2 dono 3 padhte, dono 2 likhte; read+write alag statement.*
4. 10k-row report pe `FOR UPDATE` kyun galat? → *sab lock → checkout block; MVCC snapshot use karo.*
5. `transfer` 2 rows ka naya खतरा? → *deadlock (A→B vs B→A); fix lock-order ya detect+retry.*
6. Cancel pe `FOR UPDATE` hold karke payment call — kya tootega? → *connection/lock 1-2s held → pool starve → D16 outage (data sahi, resource galat).*

## INTERVIEW LINE (ek saans)
> *"Lost update ke teen ilaj, mere project mein teeno: stock pe **no-lock atomic conditional UPDATE** (hot SKU, µs lock, rows-affected signal), order-state pe **optimistic `@Version`** (Hibernate `WHERE version=?`, loser 409, low contention), relay pe **pessimistic `FOR UPDATE SKIP LOCKED`** (`@Lock(PESSIMISTIC_WRITE)`+timeout `-2`, multi-pod job-claim). Tool contention se chunta — rare→optimistic warna retry-storm, hot-row-short→pessimistic warna convoy, single-column→atomic. DB level pe isolation default READ COMMITTED (do-statement RMW mein lost-update, isliye stock ek atomic statement), write-skew→SERIALIZABLE SSI retry, bada read→MVCC snapshot not FOR UPDATE. Aur lock kabhi remote call ke around nahi — warna pool-starvation outage."*

---
**Grounded:** `Order.java:58 @Version`, `GlobalExceptionHandler` (version→409), `inventory/ProductRepository` (`@Modifying` atomic UPDATE, `clearAutomatically/flushAutomatically`), `shopflow-outbox/OutboxRepository` (`@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout=-2` → SKIP LOCKED). `[NEW]` cases (wallet/transfer/reporting/SERIALIZABLE) reasoned, repo mein nahi — interview mein wahi honesty bolna.
