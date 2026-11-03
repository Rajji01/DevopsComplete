# 19 · Backups, RPO/RTO — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/base/postgres/backup-cronjob.yaml`, `infra/terraform/aws/rds.tf`, `docs/runbooks/RestorePostgres.md`.

## Kya (one line)
**RPO** = kitna data kho sakte (last good backup tak ka gap, *past*), **RTO** = kitni der down reh sakte (recovery time, *future*). ShopFlow dev: nightly `pg_dump` CronJob → PVC (~24h RPO); prod: RDS snapshots + PITR (~5min RPO) — aur atomic `.part→.dump` se corrupt backup kabhi "good" na dikhe.

## Easy way (Hinglish analogy)
Photos ka backup socho. **RPO** = "aakhri baar kab backup liya?" — kal raat liya to aaj ke photos kho sakte (24h gap). **RTO** = "backup se wapas laane mein kitni der?" **Atomic rename** = aadha copy hua backup ko "done" mat likho — warna zaroorat pe wahi aadha-adhoora file utha loge aur pata chalega khali hai. **Restore test** = backup kabhi khol ke dekha hi nahi to wo backup nahi, bharosa hai.

## Kyu (ye problem solve karta hai)
Data kho sakta (crash, bug, galat delete). Backup chahiye — par do sawaal alag: kitna purana data chalega (RPO) aur kitni der down (RTO). In dono ko business decide karta (orders/payments = kam RPO; dev = 24h theek).
Backup hygiene isliye: **corrupt backup jo 'good' dikhe, no-backup se bura hai** — real disaster pe pata chalega. Isliye atomic rename + delete-after-success + restore-test.

## Kaise (step by step)
1. **Dev CronJob** (`backup-cronjob.yaml`): schedule `15 2 * * *`, `concurrencyPolicy: Forbid`, `backoffLimit 2`, `activeDeadlineSeconds 1800`.
2. **Dump**: har DB (orders/inventory/notifications/payments) `pg_dump -Fc` → `/backups/$db-$ts.dump.part` → **`mv` to `.dump` on success**. Start pe purane `.part` saaf, `set -euo pipefail`.
3. **Retention**: `find /backups -name '*.dump' -mtime +7 -delete` — **naye dump succeed hone ke baad** (set -e abort karta warna).
4. **Storage**: PVC `postgres-backups` (2Gi).
5. **Prod**: prod overlay CronJob + in-cluster Postgres **delete** karta; RDS automated snapshots + **PITR** (WAL) → ~5min RPO, any-second restore.
6. **Restore** (`RestorePostgres.md`): `pg_restore` into throwaway DB + data sanity = backup proof.

## Flow diagram (nightly backup, safe)
```
 02:15  rm old *.part ; set -e
   for db in orders inventory notifications payments:
     pg_dump -Fc db  > db-ts.dump.part        (crash here → leaves .part, no restore picks it)
     mv db-ts.dump.part  db-ts.dump           ← atomic: .dump = COMPLETE
   find *.dump mtime>7 -delete                ← only AFTER new dumps succeeded
                                                 (so a failed night never deletes last good backup)
 prod: no CronJob → RDS snapshots + PITR (WAL replay) → RPO ~5min
```

## RPO/RTO tier diagram
```
 approach            RPO            RTO            tier
 ─────────────────────────────────────────────────────
 nightly pg_dump     ~24h           minutes        dev / basic
 RDS snapshots+PITR  ~5min (WAL)    15–30 min      prod / advanced
 Multi-AZ standby    ~0 (sync)      1–2 min        HA (complements backup)
 note: backups give the POINT; a standby gives the SPEED (RTO)
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Nightly `pg_dump` → PVC** *(dev)* | logical dump, CronJob | portable, granular, simple | ~24h RPO, manual restore | dev/staging |
| **RDS snapshots + PITR** *(prod)* | physical + WAL | ~5min RPO, any-second | engine-tied, all-or-nothing | prod DR |
| **Multi-AZ standby** | sync replica, auto-failover | fast RTO (~min), ~0 RPO | 2× cost, not a "backup" | HA |
| **Logical + physical both** | dump for portability + PITR | best of both | more to run | mature |

## Understanding check
1. RPO vs RTO — ek line each, kaunsa past/future?
2. Dev RPO ~24h kyu, prod ~5min kaise?
3. `.part→.dump` rename-on-success kaunsa disaster rokta?
4. Purane backups naye ke baad delete kyu (pehle kyu nahi)?
5. "Backup tested" ka matlab — file exist karna kaafi kyu nahi?

## Interview mein ye bolo
*"RPO = kitna data kho sakte (past), RTO = kitni der down (future). Dev mein nightly `pg_dump` CronJob → PVC, RPO ~24h; prod mein RDS snapshots + PITR, RPO ~5min (WAL replay, any-second restore). Job `.part` mein dump karke success pe `.dump` rename karta aur purane backups naye ke succeed hone ke baad hi delete — kyunki 'good' dikhne wala corrupt backup no-backup se bura hai. Jab tak restore test (pg_restore exit 0 + data check) nahi kiya, wo backup nahi umeed hai. Fast RTO backup nahi, Multi-AZ standby deta."*

> Related: [`../12-caching-cache-aside-failopen/`](../12-caching-cache-aside-failopen/) (Postgres source of truth), runbook `../../runbooks/RestorePostgres.md`. (Restore debug drill: planned.)
</content>
