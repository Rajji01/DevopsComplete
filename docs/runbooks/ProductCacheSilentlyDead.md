# ProductCacheSilentlyDead

**Severity:** ticket (subah dekho — users ko abhi kuch nahi dikh raha, par DB full read load le raha hai aur agli traffic spike pe wahi girega)

**Meaning:** `GET /products/{sku}` traffic chal raha hai, par products cache ke **hits zero** hain — har request Postgres tak ja rahi hai. Ye alert isliye hai kyunki cache **fail-open** hai (`LoggingCacheErrorHandler`): Redis toota/misconfigured ho to requests FAIL nahi hoti, bas cache chup-chaap band ho jaata hai (Walk 90: ElastiCache TLS vs plaintext client exactly aise hi chhup jaata).

## Check

1. **Kya Redis tak pahunch ban rahi hai?** inventory-service logs mein `LoggingCacheErrorHandler` ki warnings dhoondo:
   ```bash
   kubectl logs -n shopflow deploy/inventory-service --since=30m | grep -i "cache.*error\|RedisConnectionFailure\|lettuce"
   ```
   - Warnings hain → Redis connectivity/TLS problem (step 2).
   - Warnings NAHI hain par hits zero → cache config problem (step 3).
2. **Connectivity/TLS:**
   - `CACHE_TYPE` = `redis` hai? `REDIS_HOST` sahi endpoint? (prod: ElastiCache endpoint ConfigMap `shopflow-endpoints` se)
   - **Prod:** `REDIS_SSL=true` set hai? ElastiCache `transit_encryption_enabled=true` hai — bina TLS ke har handshake fail (Walk 90).
   - NetworkPolicy: inventory egress mein 6379 allowed hai (base mein in-cluster redis pe; prod patch VPC CIDR pe).
   - Haath se: `kubectl exec -n shopflow deploy/inventory-service -- sh -c 'exec 3<>/dev/tcp/$REDIS_HOST/6379 && echo open'` (distroless image mein shell nahi — alpine variant ya debug container lo).
3. **Config:** `spring.cache.redis.enable-statistics: true` hai (warna ye metric hi nahi banta — alert ka guard term `> 0.1` use no-data mein silent rakhta hai); key-prefix/TTL recent change?
4. **Blast radius abhi:** Grafana RED dashboard — inventory p99 aur Postgres connections dekho. Cache dead + low traffic = thik; dead + flash sale = `HighP99Latency`/pool exhaustion agla hai.

## Fix

- TLS/misconfig: env theek karo (prod overlay `REDIS_SSL`, ConfigMap `redis-host`), rollout restart, phir hits wapas aate dekho:
  ```bash
  kubectl -n shopflow rollout restart deploy/inventory-service
  watch 'curl -s localhost:8082/actuator/prometheus | grep cache_gets_total'
  ```
- Redis khud down (ElastiCache event/failover): AWS console events; multi-az failover khud hota hai — alert clear hone tak DB load par nazar.

**Why ticket, not page:** fail-open ka matlab hi ye hai ki users abhi theek hain; page tab banta jab `HighP99Latency`/SLO burn saath fire ho (wo apne aap page karenge).

**Related:** [EASY-NOTES §12/§13](../EASY-NOTES.md), Walk 90/91 (SELF-WALK), D10 (redis-down thundering herd drill).
