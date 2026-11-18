# PostgresBackupMissed

**Severity:** ticket (aaj hi dekho — data abhi safe hai, par RPO ghadi tik rahi hai: jitne din backup nahi, utna data ek disk-loss mein jaayega)

**Meaning:** `postgres-backup` CronJob ne **26h+ se koi successful run nahi** diya — ya fail ho raha hai, ya chal hi nahi raha (suspended, schedule typo, PVC full/unbound). Ye freshness alert hai, failure-count nahi: "kabhi trigger hi nahi hua" bhi isi mein pakda jaata hai (Walk 92).

**Scope note:** dev/minikube overlay ka hi concern hai — prod overlay in-cluster Postgres + ye CronJob delete karta hai (RDS snapshots + PITR, `rds.tf`). Metric `kube-state-metrics` se aata hai (lab cluster ka scrape setup, Walk 51 wali class).

## Check

```bash
# 1. CronJob ki haalat: suspended? last schedule/success kab?
kubectl -n shopflow get cronjob postgres-backup -o wide

# 2. Pichhle runs — failed jobs history mein dikhte hain (limit 3):
kubectl -n shopflow get jobs -l app.kubernetes.io/name=postgres-backup
kubectl -n shopflow logs job/<latest-failed-job>

# 3. Aam causes, isi order mein:
#    - PVC full: `ls -lh /backups` job log mein / `kubectl describe pvc postgres-backups` (2Gi hi hai)
#    - postgres unreachable / password rotate hua (secret shopflow-db / postgres-password)
#    - activeDeadlineSeconds 1800 cross (DB bada ho gaya) -> job DeadlineExceeded
#    - NetworkPolicy: postgres-backup-egress sirf postgres:5432 allow karta hai — DNS change?
```

## Fix

- Cause ke hisaab se (space do / secret theek karo / deadline badhao), phir **haath se ek run karake verify karo**:
  ```bash
  kubectl -n shopflow create job --from=cronjob/postgres-backup backup-manual-$(date +%s)
  kubectl -n shopflow logs -f job/backup-manual-<ts>
  ```
- Success ke baad bhi: **restore drill** ka time ho to [RestorePostgres.md](RestorePostgres.md) chalao — backup jo kabhi restore nahi hua, wo backup nahi, umeed hai.

**Related:** [RestorePostgres.md](RestorePostgres.md) · EASY-NOTES (backups/RPO-RTO) · Walk 92 (SELF-WALK).
