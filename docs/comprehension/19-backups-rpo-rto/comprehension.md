# 19 · Backups, RPO/RTO, and the backup that was secretly corrupt — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/base/postgres/backup-cronjob.yaml`, `infra/terraform/aws/rds.tf` (prod), `docs/runbooks/RestorePostgres.md`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's backup design. Read once, then answer.*

Two numbers define any backup strategy. **RPO (Recovery Point Objective)** — how much data you can afford to **lose** (the gap between "now" and the last good backup). **RTO (Recovery Time Objective)** — how **long** you can afford to be down while restoring. They're different questions: RPO is about the *past* (how far back do we snap to), RTO is about the *future* (how fast are we back).

In dev, ShopFlow backs up with a nightly **CronJob** (02:15 UTC, after the day's traffic): a `pg_dump -Fc` of each of the four databases into a PVC, keeping 7 days. A nightly logical dump means the RPO is **up to ~24 hours** — a crash at 2am loses everything since the last 2:15 dump — and the RTO is however long a `pg_restore` of that dump takes (~minutes for this data). That's fine for a learning/dev environment. In **prod on AWS**, the in-cluster Postgres (and this CronJob) are **deleted** by the prod overlay; RDS provides **automated snapshots + point-in-time recovery (PITR)**, which replays the transaction log, so the RPO drops to **~5 minutes** and you can restore to *any* second in the retention window — a completely different tier.

The backup job has one detail that looks fussy and is the whole point. It dumps to a **`.part`** file and only **renames it to `.dump` on success** (`pg_dump … > "$db-$ts.dump.part"` then `mv … "$db-$ts.dump"`). It also clears leftover `.part` files at the start, runs with `set -euo pipefail`, and only deletes old backups **after** the new dumps succeed. Why so careful? Because a **failed or truncated dump that still has a `.dump` name is worse than no backup** — a restore would pick that corrupt file, believing it's good, and you'd discover the corruption only during a real recovery. The atomic rename guarantees a file named `.dump` is a **complete** dump; a crashed run leaves a `.part`, which no restore ever selects.

The runbook (`RestorePostgres.md`) makes the other half explicit: **a backup you have never restored is not a backup, it's a hope.** You test the restore path, or you don't actually have one.

---

## Questions

### A — Literal
1. Define RPO and RTO in one line each, and say which one is about the past and which about the future.
2. What is dev's RPO with a nightly dump, and what gives prod a ~5-minute RPO instead?
3. What does the job do with a dump file *before* it's named `.dump`, and why?

### B — Inference
4. The job renames `.part → .dump` only on success and deletes old backups only after the new ones succeed. What specific disaster does each of those two orderings prevent?
5. Prod deletes the CronJob entirely and relies on RDS. Why is PITR a different *tier* of RPO than a nightly dump, not just a faster version of the same thing?

### C — Trade-off
6. Logical backups (`pg_dump`) vs physical/PITR (RDS snapshots + WAL). What does `pg_dump` give you that a raw snapshot doesn't, and what does PITR give that a nightly dump can't?
7. The dev RPO is ~24h and nobody's fixing it. When is a 24-hour RPO a perfectly reasonable choice, and when would it be negligent?

### D — Transfer / extension
8. Finance asks for a **1-minute RPO and 5-minute RTO** for orders in prod. Which parts of the current design already meet it, and what (if anything) must change?
9. You're asked to prove the backups work. Design the smallest **restore test** you'd run regularly, and name the two things it must verify beyond "a file exists".

### E — Spot-the-flaw
10. A dev "simplifies" the job: `pg_dump … > "$db.dump"` directly (no `.part`), and moves the `find … -delete` of old backups to the **start** of the script "to free space first". One night `pg_dump` fails halfway (disk full). Describe the two ways this now leaves you with **no usable backup**, and how the original design avoided both.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **RPO** = how much data you can afford to **lose** — the gap to the last good backup (about the **past**). **RTO** = how **long** you can afford to be down during recovery (about the **future**).
2. Dev RPO ≈ **24 hours** (a once-a-nightly dump, so a crash loses everything since the last 2:15 dump). Prod gets ~**5 minutes** from **RDS point-in-time recovery (PITR)**, which replays the WAL/transaction log, not just restores a nightly file.
3. It writes to a **`.part`** file and only **renames to `.dump` on success**, so a file named `.dump` is always a **complete** dump — a crashed/truncated run leaves a `.part`, which no restore selects.
</details>

<details>
<summary>B — Inference</summary>

4. **Rename-on-success** prevents a **truncated/corrupt dump from masquerading as a good backup** (a restore would pick it and fail during a real recovery). **Delete-old-only-after-new-succeeds** prevents **deleting your last good backup before the new one exists** — if tonight's dump fails, yesterday's is still there; if you deleted first, a failed dump would leave you with **nothing**.
5. A nightly dump is a **discrete point** — you can only restore to 2:15am each day, so your RPO is the whole day. **PITR** continuously archives the **transaction log**, so you can restore to **any second** in the retention window — the recovery point is continuous, not daily. That's a structural difference (continuous log vs periodic snapshot), which is why it's a different tier, not just "the dump but more often".
</details>

<details>
<summary>C — Trade-off</summary>

6. **`pg_dump` (logical)** gives a **portable, version-independent, selective** backup — you can restore a single table or database, move across Postgres versions/hosts, and read what's inside; it's also slower and point-in-time-only. **PITR (physical + WAL)** gives **continuous, near-zero-RPO** recovery of the whole cluster to any instant, and fast whole-DB restore, but it's tied to the engine/version and is all-or-nothing (not "just this table"). You often want both: PITR for disaster recovery, logical dumps for portability and granular restores.
7. A ~24h RPO is **reasonable** when the data is **reproducible or low-value** (a dev/staging env you can reseed, caches, derived data) or when losing a day costs less than the engineering to do better. It's **negligent** when the data is **irreplaceable and valuable** — real customer orders and payments — where losing a day of transactions is a financial/legal problem. The right RPO is a business decision per dataset, not a default.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Already met:** prod's **RDS PITR** gives ~5-minute (really near-continuous) RPO, which **beats** the 1-minute ask if WAL archiving is frequent; RTO depends on instance size/restore speed — RDS PITR/restore is typically **15–30 min**, so the **RTO target (5 min) may not be met** by a full restore. What changes: for a 5-minute RTO you'd need **failover to a standby** (Multi-AZ RDS promotes a replica in ~1–2 min) rather than restoring from a snapshot — i.e. high **availability** (hot standby) complements backups (cold recovery). Backups give you the *point*; a standby gives you the *speed*.
9. A **periodic automated restore test**: take the latest `.dump` (or an RDS snapshot), **restore it into a throwaway database**, and verify (a) **`pg_restore` exits 0** (the file is structurally complete and loadable — not just present), and (b) a **data sanity check** passes (e.g. row counts / a known recent order exists / referential checks) — proving the backup actually contains usable, consistent data, not an empty or stale file. "A file exists" proves nothing; "it restores and the data is there" is a backup.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. **(1) Corrupt-masquerading-as-good:** writing straight to `"$db.dump"` means a half-finished dump (disk full) is left with the **final name**, so a future restore picks a **truncated, unrestorable** file believing it's a valid backup — you find out only mid-disaster. **(2) Deleted last good backup:** moving `find … -delete` to the **start** removes the previous good backups **before** tonight's dump has succeeded — so after the failed dump you have **no** recent backup at all. The original design avoids both: it writes to `.part` and **renames only on success** (so `.dump` always means complete), and it deletes old backups **only after** the new dumps succeed (so a failed run never costs you your existing backups) — plus `set -euo pipefail` aborts the whole script on the first failure. The "simplification" removed two safety orderings that exist precisely for the night a dump fails.
</details>

---

## Tod ke dekho — Local (minikube / compose psql)

```bash
# the CronJob is in the dev overlay; you can run its logic by hand against the compose Postgres:
make up
# dump all four DBs the way the job does (custom format, atomic .part -> .dump):
for db in orders inventory notifications payments; do
  docker compose -f shopflow/docker-compose.yml exec -T -e PGPASSWORD=postgres postgres \
    sh -c "pg_dump -h localhost -U postgres -Fc $db > /tmp/$db.dump.part && mv /tmp/$db.dump.part /tmp/$db.dump && echo $db ok"
done

# PROVE the restore works (the only test that counts): restore orders into a throwaway DB and check
docker compose -f shopflow/docker-compose.yml exec -T -e PGPASSWORD=postgres postgres sh -c '
  createdb -h localhost -U postgres orders_restore_test &&
  pg_restore -h localhost -U postgres -d orders_restore_test /tmp/orders.dump && echo "pg_restore exit=$?" &&
  psql -h localhost -U postgres -d orders_restore_test -c "select count(*) from orders;" &&
  dropdb -h localhost -U postgres orders_restore_test'
# -> pg_restore exit=0 + a real row count = a backup you can trust; a file alone is not.
```

**Proved:** the atomic `.part → .dump` produces a complete file, and a **restore into a scratch DB** (exit 0 + row count) is the only thing that proves the backup is real. In minikube, the actual CronJob runs on schedule (`kubectl -n shopflow get cronjob postgres-backup`).

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A/B** — any Postgres (compose or Cloud Shell). RDS PITR needs an AWS account (`infra/terraform/aws/rds.tf`).

---

## Interview concepts is passage se
- **RPO vs RTO** — data-loss window (past) vs downtime window (future).
- **Logical (`pg_dump`) vs physical/PITR** — portable/granular vs continuous/near-zero-RPO.
- **Basic → advanced** — nightly CronJob to a PVC (dev, ~24h RPO) vs RDS snapshots + PITR (prod, ~5min).
- **Backup hygiene** — atomic `.part→.dump`, delete-old-after-new-succeeds, `set -e` — a corrupt backup is worse than none.
- **Backups ≠ HA** — PITR gives the point; a Multi-AZ standby gives the fast RTO.
- **"Untested backup = hope"** — a restore test (exit 0 + data sanity), not "a file exists".

**Interviewer's lens:** RPO/RTO ka clear farak, "corrupt backup worse than none" ka hygiene insight, aur "backup tested hai?" — ye SRE/prod round ke signatures hain.

> Interview line: *"RPO = kitna data kho sakte (past), RTO = kitni der down (future). Dev mein nightly pg_dump CronJob → PVC, RPO ~24h; prod mein RDS snapshots + PITR, RPO ~5min (WAL replay, any-second restore). Job `.part` mein dump karke success pe `.dump` rename karta aur purane backups naye ke succeed hone ke baad hi delete — kyunki corrupt backup jo 'good' dikhe wo koi backup se bura hai. Aur jab tak restore test nahi kiya (pg_restore exit 0 + data check), wo backup nahi, umeed hai. Fast RTO ke liye backup nahi, Multi-AZ standby chahiye."*
</content>
