# Restore PostgreSQL (procedure)

Two environments, two mechanisms:

| | dev / minikube (in-cluster Postgres) | prod (RDS) |
|---|---|---|
| Backup | nightly `pg_dump -Fc` per DB → PVC `postgres-backups` (`k8s/base/postgres/backup-cronjob.yaml`) | automated snapshots (7 days) + point-in-time recovery (`rds.tf`) |
| RPO | up to 24 h | 5 minutes (PITR) |
| RTO | ~10 min | 15–30 min (new instance from snapshot) |

## Dev: restore one database from last night's dump
```sh
kn get pods -l app.kubernetes.io/name=postgres-backup          # last job succeeded?
kn run restore --rm -it --image=postgres:16-alpine --restart=Never --overrides='{"spec":{"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"postgres-backups"}}],"containers":[{"name":"restore","image":"postgres:16-alpine","command":["sh"],"stdin":true,"tty":true,"volumeMounts":[{"name":"b","mountPath":"/backups"}]}]}}'
# inside the pod:
ls -lh /backups
export PGPASSWORD=postgres
pg_restore -h postgres -U postgres -d orders --clean --if-exists /backups/orders-<ts>.dump
```
Then `kn rollout restart deploy/order-service` (Hikari connections may hold stale state) and run the smoke test (`make token` + one order).

## Prod: point-in-time recovery on RDS
1. `aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier shopflow --target-db-instance-identifier shopflow-restore --restore-time <UTC timestamp>` (same subnet group / SG / parameter group as `rds.tf`).
2. Verify data on the restored instance (`psql` from a pod in the VPC).
3. Repoint: update `rds-endpoint` in the prod `shopflow-endpoints` ConfigMap (git + Argo CD sync), **or** rename instances so the old endpoint stays valid.
4. Rolling restart of the three services; smoke test; watch `HighErrorRate`.
5. Afterwards: Terraform state now differs from reality → `terraform import` / adjust `rds.tf`, then post-mortem.

**Practice this quarterly.** A backup that was never restored is a hope, not a backup.
