# InventoryCircuitOpen (page)

**Fires when:** order-service's circuit breaker has been rejecting calls to inventory-service for 2 minutes → every new order returns 503 and is marked FAILED. Order *reads* still work.

## 1. Is inventory-service alive?
```sh
kn get pods -l app.kubernetes.io/name=inventory-service
kn describe pod -l app.kubernetes.io/name=inventory-service | grep -A5 -E 'Last State|Readiness'
kn logs deploy/inventory-service --since=5m | tail -50
kn exec deploy/order-service -- wget -qO- -T 2 http://inventory-service:8082/actuator/health/readiness
```
| Finding | Cause | Fix |
|---|---|---|
| pods not Ready, readiness failing on `db` | inventory's Postgres/RDS down or pool exhausted | fix the DB (ServiceDown.md for Postgres); pods recover without restart |
| pods Running but `wget` times out | NetworkPolicy / Service selector / DNS | `kn get endpoints inventory-service` empty? compare labels |
| pods CrashLoopBackOff | bad release / config | rollback inventory-service |
| pods fine, breaker still open | breaker needs 10 s + 2 successful half-open calls; or inventory is slow (> 2 s read timeout) | check inventory p99; if slow, see HighP99Latency |

## 2. After recovery
FAILED orders are settled automatically by the reconciler within ~3 minutes (release + CANCELLED). Check: `select status, count(*) from orders where updated_at > now() - interval '1 hour' group by 1;`

## 3. Verify
`resilience4j_circuitbreaker_state{state="closed"} == 1` and new orders CONFIRMED.
