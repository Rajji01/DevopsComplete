# HighP99Latency (ticket)

**Fires when:** p99 latency of a service is above 1 s for 10 minutes. Not an outage, but the SLO budget is burning.

## 1. Where is the time going?
Tempo → search `service.name=<service> duration>1s` → open the slowest traces → longest span:
- DB span slow → `EXPLAIN ANALYZE` the query; missing index? lock waits? (`pg_stat_activity`, RDS Performance Insights top SQL)
- downstream HTTP span slow → that service has the problem, follow its runbook
- Kafka send slow → broker / `acks=all` with a lagging replica
- no slow span but slow total → GC pauses (`jvm_gc_pause_seconds`), CPU throttling, thread starvation

```sh
kn top pods                                          # CPU at request? -> HPA should scale; is it?
kn get hpa                                           # maxReplicas reached?
curl -s localhost:8081/actuator/prometheus | grep -E 'hikaricp_connections_(active|pending)|jvm_gc_pause_seconds_sum'
```

## 2. Mitigate
Scale manually if HPA is at max (`kn scale deploy/<service> --replicas=N`); enable the cache / raise TTL; shed load (rate limit). A slow release → rollback.

## 3. Verify / close
p99 under 500 ms for 15 minutes. Create a ticket with the slow trace attached.
