# HighErrorRate (page)

**Fires when:** > 5% of a service's requests return 5xx for 5 minutes. Users are seeing errors right now.

## 1. Which service, since when, what changed?
```sh
kn get pods -o wide                                   # restarts? new pod template hash = recent deploy?
kn rollout history deploy/<service>                  # was there a deploy in the last 30 min?
kn logs deploy/<service> --since=10m | grep -E 'ERROR|Exception' | sort | uniq -c | sort -rn | head
```
Grafana → *ShopFlow – RED + saga* → Error ratio panel: one service or all? Tempo → search `status=error service.name=<service>` → open a trace: which span fails?

## 2. Likely causes (in order of frequency)
| Symptom in logs/traces | Cause | Fix |
|---|---|---|
| Just deployed, errors started with the new pods | bad release | **rollback now** ([Rollback.md](Rollback.md)), investigate later |
| `HikariPool ... Connection is not available` | DB pool exhausted / DB slow | check Postgres/RDS CPU and connections; scale pods down, not up |
| `CallNotPermittedException` / `ResourceAccessException` to inventory | downstream down | see [InventoryCircuitOpen.md](InventoryCircuitOpen.md) |
| `OutOfMemoryError`, OOMKilled restarts | memory leak or too-small limit | restart to mitigate; raise limit; heap dump |
| 5xx only on one path | code bug | feature flag / rollback |

## 3. Verify
Error ratio back under 1% for 5 minutes; alert resolves itself (`send_resolved`).

## 4. Escalate
If not mitigated in 15 minutes or the cause is the database/Kafka: page the platform owner. Open a post-mortem.
