# 30 · Replication / DB-per-service / multi-instance — NOTES

> Pair: [`comprehension.md`](comprehension.md). "Data kahan rehta, kaise replicate hota, aur kahan tootta."

---

## KYA (one breath)
**Data boundary = failure mode.** ShopFlow: **DB-per-service** (4 alag DB → no cross-service txn/JOIN → outbox/saga), **many pods/one DB** (concurrency DB pe + connection ceiling), **replication** prod mein (sync standby = HA, async replica = read-scale+lag, CDC = logical, multi-primary = conflict).

## EASY WAY
> **"Alag DB → ek transaction do DB pe nahi → outbox/saga. Ek DB bahut pods → lock DB pe, connection ceiling. Replica sync = safe par slow, async = fast par purana/lossy. Mode ko data se match karo."**

## 3 TOPOLOGY CHOICES + issues
| Choice | ShopFlow | Milta | Issue (kya tootta) |
|--------|----------|-------|--------------------|
| **DB-per-service** | orders/payments/inventory/notifications alag | independent deploy/scale, blast-radius | **no cross-service txn/JOIN** → dual-write → outbox+saga, eventual consistency |
| **Many instances, 1 DB** | N pods → ek service-DB | horizontal compute | concurrency DB pe (locks/SKIP LOCKED); **ceiling pods×pool ≤ max_connections** (D16) |
| **Replication** | prod RDS Multi-AZ+PITR; replicas=scale step | HA + read-scale | lag (read-your-writes), failover loss, split-brain |

## REPLICATION STRATEGIES (DB level — "diff strategy db level")
| Strategy | Kya | For | Cost / issue | ShopFlow |
|----------|-----|-----|--------------|----------|
| **Synchronous standby** | commit tab ack jab standby ke paas (Multi-AZ) | **HA / zero-loss failover** | write-latency; standby down→write wait | ✅ RDS Multi-AZ (prod) |
| **Async read replica** | WAL stream, replica lag | **read scale / offload** | **lag → stale/read-your-writes**; tail-loss on failover | ✖ `[NEW]` (HLD §5 scale) |
| **Logical / CDC** | per-table row changes (WAL tail) | feed search/warehouse/Kafka | infra (Debezium/Connect) | ✖ (relay=poll alt; topic 26) |
| **Multi-primary / active-active** | 2 primaries dono likhte | multi-region write | **write-write conflict, split-brain** | ✖ `[NEW]` (HLD §5 multi-region + idempotency) |

- **Sync vs async = Kafka acks=all/minISR ka shape** (topic 23): durability vs latency/availability. Same dial as **CAP/PACELC** (topic 25).

## DIAGRAM — DB-per-service (kyun outbox/saga)
```
 order-service ──owns──► [orders DB]      payment-service ──owns──► [payments DB]
        │  (no cross-DB txn, no JOIN)                   ▲
        └── order + outbox_event = ONE local txn ──► relay ──Kafka──► idempotent consumer
            (atomicity sirf ek DB mein possible → dual-write solved by outbox; cross-service = saga)
```

## DIAGRAM — replication modes
```
 SYNC standby (Multi-AZ):  write ──► PRIMARY ──wait──► STANDBY ack ──► client OK
     failover: promote STANDBY, ZERO loss.   (not readable; HA only)

 ASYNC replica:   write ──► PRIMARY ──OK(client)
                                 └─lag─► REPLICA (reads)   ← may be ms-sec behind
     read-your-writes MISS if read hits replica right after write.
     failover: last un-shipped writes LOST.
```

## MULTI-INSTANCE WRITE (same-service pods, ek DB)
```
 pod1 ─┐
 pod2 ─┼──► orders DB (shared truth)   concurrency resolved HERE:
 pod3 ─┘      stock  -> atomic UPDATE (row lock)
              state  -> @Version (optimistic)
              relay  -> FOR UPDATE SKIP LOCKED (pods divide rows, once-each)
 limit: pods × DB_POOL_SIZE ≤ max_connections  (warna D16 outage)
```

## ISSUES MATRIX ("kya kya issue aate")
| Setup | Issue | Fix |
|-------|-------|-----|
| diff services, diff DB | dual-write, no atomic cross-service | outbox + saga + idempotency (01/25) |
| diff services, **same DB** (anti-pattern) | coupling, blast-radius, saga bypass | **mat karo**; keep boundary |
| same service, many pods, 1 DB | lost-update, hot-row, conn ceiling | locks/SKIP LOCKED + PgBouncer (26/28) |
| async replica | read-your-writes stale | primary-pin / LSN-wait fresh reads |
| sync standby read | not allowed / slows writes | use async replicas for reads |
| multi-primary | write-conflict, split-brain | region-owns-user / CRDT / version-vector |
| cross-region replay | duplicate apply | **idempotency keys** (already there) |

## KYU (key reasons)
- **Ek ACID txn = ek DB.** Do DB/system pe atomic nahi (2PC avoid) → eventual consistency engineer karni padti.
- **Sync standby read ke liye nahi:** ek hi hai + har write use wait karta → read-scale nahi; HA ≠ read-scale (alag replicas).
- **Async lag → stale:** replica ms-sec peeche; apni write turant padho to purana.
- **Multi-primary LWW-by-clock khatarnak:** clock skew → purana edit naya ko haraa de, silent loss.
- **Idempotency ≠ conflict-resolution:** replay safe karta (same op), do alag concurrent edits ka winner decide nahi karta.

## UNDERSTANDING CHECK (khud bol)
1. DB-per-service mein kya 2 cheez nahi kar sakte? → *cross-service txn + cross-service JOIN.*
2. Multi-AZ standby se read kyun nahi? → *HA target hai, readable nahi; sync read writes slow karta; read-scale async replicas se.*
3. "Order placed par list empty, kabhi-kabhi" — kyun? → *GET async replica pe gaya jo abhi lagged; read-your-writes miss.*
4. Payments vs view-counter — replication mode? → *payments sync (zero-loss), counter async (sasta, loss ok).*
5. Cross-service "orders + payment status" report bina JOIN? → *materialized view (CDC) ya API composition.*
6. Multi-primary LWW-by-wall-clock ka खतरा? → *clock skew → naya edit silently haar jaata.*

## INTERVIEW LINE
> *"Data boundary failure-mode decide karta. **DB-per-service** (4 alag DB) → cross-service txn/JOIN nahi → **outbox+saga**, eventual (2PC avoid). Ek service ke **bahut pods ek DB** → concurrency DB pe (atomic UPDATE/`@Version`/relay `SKIP LOCKED`), ceiling `pods×pool≤max_connections`. Replication: **Multi-AZ sync standby** = HA/zero-loss, read ke liye nahi; **async read-replica** = read-scale par **lag→read-your-writes stale** (fresh read primary/LSN-wait); **CDC** logical; **multi-primary** = conflict/split-brain, idempotency sirf replay safe karta. Mode data se match — payments sync, counter async."*

---
**Grounded:** docker-compose (4 DBs on one postgres; each service `DB_URL=.../owndb`), `ARCHITECTURE.md` (RDS Multi-AZ+PITR), `HLD-SYSTEM-DESIGN.md` §5 (read replicas, multi-region RDS, idempotency already there), relay `FOR UPDATE SKIP LOCKED`, outbox/saga (01/25). `[NEW]` = read-replica/multi-primary, prod/scale me, repo mein nahi.
