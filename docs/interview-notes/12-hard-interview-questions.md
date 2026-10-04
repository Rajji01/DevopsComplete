# 12 — Tagde Sawaal: 64 Hard Interview Questions with Model Answers

> For the second/third technical round and the "bar raiser". Every answer is structured so you can speak it in 2–4 minutes, and each one points at the ShopFlow file that proves you have done it (`shopflow/...`, `infra/terraform/aws/...`). Where ShopFlow does *not* have something, the answer says so — "here is how I would add it" beats bluffing every time.
>
> Reading order: skim the question list, answer out loud, then read the model answer. Do not memorise text; memorise the **structure** and the **numbers**.

> Tip: Hard round mein interviewer answer nahi, *thinking* dekh raha hai. "Assumptions → numbers → design → failure modes → trade-offs" — yeh sequence har system design answer ka skeleton hai.

---

## PART A — SYSTEM DESIGN (6 questions, 5-step answers)

The 5 steps used in every answer: **(1) Requirements + assumptions → (2) Capacity estimate → (3) High-level design → (4) Deep dive on the hard part → (5) Failure modes + trade-offs.**

### A1. Design a flash-sale checkout for 1M users (10k units of one product, sale opens at 12:00)

**1. Requirements.** Functional: reserve, pay, confirm; never oversell; one unit per user; fairness "good enough". Non-functional: peak arrives in the first 10 s; p99 < 500 ms for "did I get it?"; downstream payment provider limited to ~500 TPS.

**2. Capacity.** 1M users, 60% click in the first 10 s → 60k RPS peak on "buy". 10k units → 99% of requests must get a fast "sold out". Payload ~1 KB → 60 MB/s ingress. Payment: 10k calls spread over minutes, not seconds.

**3. High-level design.**
```
CDN/ALB + WAF (rate limit per IP/user) ──▶ edge gate (Redis atomic counter: DECR stock_token) ──▶ 90%+ rejected here, <5 ms
   ──▶ winners get a reservation token ──▶ Kafka "checkout.requests" (key=userId) ──▶ checkout workers (bounded concurrency)
   ──▶ DB: reservation row + outbox ──▶ payment (idempotency key = reservationId) ──▶ confirm/expire after 10 min
```
- **Pre-sale**: warm caches, pre-create 10k tokens in Redis (`SET stock:PS5 10000`), pre-scale pods (HPA is too slow for a 10-s spike; schedule a `kubectl scale`/KEDA cron), pre-connect pools.
- **Gate**: `DECR` is atomic and single-threaded in Redis; ≤ 0 → "sold out" immediately. `SETNX user:{id}` for one-per-user. Redis does ~100k ops/s per node; shard by SKU if many products.
- **Queue**: winners (10k) flow through Kafka so the DB and payment see a smooth ~500 TPS, not 60k.
- **Durability**: the DB is the source of truth; Redis count is a *fast approximation*. The DB does the final `UPDATE product SET quantity = quantity - :qty WHERE sku = :sku AND quantity >= :qty` — exactly ShopFlow's `ProductRepository.decrementStock` (`inventory-service/.../product/ProductRepository.java`), so even if Redis over-admits by a few, the DB refuses.

**4. Deep dive: no oversell + no double-charge.** Reservation holds stock 10 min (TTL); payment uses an idempotency key (A4); expiry job releases stock (compensation = ShopFlow's `ReservationService.release`). Exactly-once between "paid" and "confirmed" is done with the outbox (`order-service/.../outbox/`) and an idempotent consumer (`notification-service/.../OrderEventListener.java` with the `processed_event` table).

**5. Failure modes and trade-offs.** Redis dies → fail closed (reject) or fall back to DB with a lower admission rate; Kafka lag → users see "processing" (async UX, tell them); fairness vs throughput (a strict FIFO queue is fairer but slower; a Redis token is faster but "first packet wins"); bots → WAF, CAPTCHA, device fingerprint; thundering herd at 12:00:00 → client-side jitter (0–3 s random delay baked into the app).

Numbers to say: Redis DECR ~0.1 ms; ALB scales but needs pre-warming for 60k RPS (ask AWS); 10k units × 1 payment call × 2 s = ~5.5 h at 1 TPS, so 500 TPS parallel is fine.

### A2. Design a notification system (email/SMS/push, 50M notifications/day, multi-tenant)

**1. Requirements.** Channels: email, SMS, push; templates; user preferences + quiet hours; at-least-once with dedup; priority (OTP in < 5 s vs marketing in < 1 h); provider failover; delivery tracking.

**2. Capacity.** 50M/day ≈ 580/s average, 10× peak → ~6k/s. Each event ~2 KB → ~1 TB/month of raw events, stored 30 days for audit. OTP volume ~5% but highest priority.

**3. Design.**
```
producers ─▶ Kafka "notifications.requested" (partitions by userId; separate topic per priority: otp / transactional / marketing)
  ─▶ preference + rate-limit service (Redis: per-user daily caps, quiet hours, unsubscribed) 
  ─▶ template renderer ─▶ channel workers (email via SES/SendGrid, SMS via SNS/Twilio, push via FCM/APNs)
  ─▶ provider adapters with circuit breaker + fallback provider ─▶ "notifications.status" topic ─▶ status store (Postgres/DynamoDB) ─▶ webhooks/dashboards
DLT per channel for poison messages; retry topics with 1m/10m/1h backoff
```
**4. Deep dive: at-least-once + dedup + ordering.** Kafka gives at-least-once; a `processed_event(event_id PK)` table per worker (exactly ShopFlow's `ProcessedEvent`) ensures a crash after send and before commit does not send twice *if* send + insert are in one DB transaction and the provider call is idempotent (most providers accept an idempotency key / message ID). Priority isolation: separate topics + consumer groups + pod pools so marketing bursts never delay OTPs. Retry with a non-blocking retry topic chain (Spring Kafka `@RetryableTopic`) instead of in-partition blocking retries (ShopFlow uses blocking `DefaultErrorHandler` with `ExponentialBackOff` max 10 s then DLT — fine for low volume, wrong at 6k/s).

**5. Failures.** Provider outage → breaker opens (`resilience4j` config as in `order-service/application.yml`), route to secondary provider; Kafka rebalance storm from slow workers → `max.poll.interval.ms` tuning, static membership; duplicate due to provider retry → dedup key in the provider call; template bug → canary 1% of marketing traffic; cost → SMS is expensive, prefer push, cap per user.

### A3. Design a distributed rate limiter (10k RPS per API key, 1000 keys, across 50 pods)

**1. Requirements.** Limit per key and per endpoint; accurate within ~1%; latency added < 2 ms; survive the limiter store failing (fail open or closed? decide per API: auth endpoints fail closed, read endpoints fail open).

**2. Capacity.** 1000 keys × 10k = up to 10M decisions/s worst case; realistically 500k/s. A single Redis handles ~100k–200k ops/s → shard by key (Redis Cluster, 8 shards) or do local pre-filtering.

**3. Algorithms.**
| Algorithm | Memory | Behaviour | Use |
|---|---|---|---|
| Fixed window | 1 counter | 2× burst at window edge | crude |
| Sliding log | O(requests) | exact | tiny limits |
| Sliding window counter | 2 counters | ~1% error, smooth | most APIs |
| Token bucket | 2 numbers (tokens, timestamp) | allows bursts up to bucket size, steady refill | **default choice** |
| Leaky bucket | queue | smooths output | shaping |

**4. Deep dive.** Token bucket in Redis with a Lua script (atomic read-modify-write): `tokens = min(cap, tokens + (now - ts) * rate); if tokens >= 1 then tokens -= 1; allow`. One round-trip. For 50 pods: **two-tier**: each pod keeps a local token bucket with 1/50th of the budget plus borrows from Redis in batches of 100 tokens (reduces Redis ops 100×, accuracy error ≤ batch size). Return `429` + `Retry-After` + `X-RateLimit-Remaining`. ShopFlow has the **single-pod** version: `@RateLimiter(name="orders")` on `OrderController.create` with `limit-for-period: 50 / 1s / timeout-duration: 0` — I'd say openly "that is per pod, so the real limit is 50 × replicas; for a global limit I'd move it to Redis or to the ALB/WAF rate-based rule".

**5. Failures/trade-offs.** Redis down → local bucket only (degraded but safe); clock skew between pods → use Redis `TIME` in Lua, never pod clocks; hot key (one big tenant) → shard the key by `key:slot`; where to enforce: edge (WAF/API gateway, cheapest, coarse) vs service (fine-grained, costs latency) — do both.

### A4. Design an idempotent payment API

**1. Requirements.** `POST /payments` may be retried by clients/networks; the same logical payment must charge once; concurrent duplicates must not double-charge; result must be replayable for 24 h.

**2. Numbers.** 2k payments/s peak, keys 36-char UUID, 24-h retention = 170M keys × ~300 B ≈ 50 GB → DynamoDB/Redis with TTL, or Postgres partitioned by day.

**3. Design.**
```
client generates Idempotency-Key (UUID v4), sends it with POST
server: INSERT INTO idempotency(key, request_hash, status='IN_PROGRESS') — unique PK
   ├─ insert OK           → do the work → UPDATE status='DONE', response_body
   ├─ duplicate, DONE     → return stored response (same status code)
   ├─ duplicate, IN_PROGRESS → 409 "retry later" (or wait on it for ≤ 2 s)
   └─ same key, different request_hash → 422 (client bug)
PSP call carries the same key (Stripe-style) so a crash between our commit and PSP's response is reconciled, not re-charged
```
**4. Deep dive: the race.** Two first-time requests arrive 1 ms apart. Both `SELECT` → not found → both proceed. The fix is a **unique constraint**, not a check-then-act: the second `INSERT` fails with a constraint violation and the handler maps it to a conflict/replay. ShopFlow does exactly this: `Order.idempotencyKey` is `unique = true` (`order-service/.../order/Order.java`, `V1__create_orders.sql`), `OrderService.placeOrder` returns the existing order with `created=false` → `OrderController` answers **200 instead of 201**, and `GlobalExceptionHandler` turns `DataIntegrityViolationException` from the concurrent loser into a 409. Downstream, `Reservation.orderRef` is unique in inventory-service so the retried `reserve` is also safe — idempotency at **two** layers, because retries happen at two layers (`@Retry` in `InventoryClient`).

**5. Trade-offs.** Key scope = per client/tenant (prefix with client id, otherwise clients collide); TTL 24 h; store the response, not just "done"; status machine for long-running work; fingerprint the body to catch misuse; the PSP must be idempotent too or you need reconciliation jobs (nightly compare of our ledger vs PSP report).

### A5. Design multi-region active-passive for ShopFlow (RPO ≤ 1 min, RTO ≤ 15 min)

**1. Requirements.** Survive a full region outage; data loss ≤ 1 min; recover ≤ 15 min; cost ≤ 1.5× single region; no split brain.

**2. Numbers.** DB 50 GB, change rate ~5 MB/s peak → cross-region replication bandwidth trivial; Kafka 7-day retention 300 GB; images 14 GB.

**3. Design.**
```
Route 53: shop.example.com  failover policy  primary=ap-south-1 ALB (health check), secondary=ap-southeast-1 ALB
ap-south-1 (active)                                  ap-southeast-1 (passive, "warm standby")
EKS (3 nodes) ──────────── same Terraform, var.region ─▶ EKS (1–2 nodes, HPA min 1)
RDS primary ─────── cross-region read replica (async, lag seconds) ─▶ RDS replica (promote on failover)
MSK ─────────────── MSK Replicator / MirrorMaker 2 ─▶ MSK (topics mirrored, consumer offsets translated)
ECR ─────────────── replication rule ─▶ ECR
Secrets Manager ─── replica secret ─▶ Secrets Manager
Cognito: user pool is regional and NOT replicable → either put it in the passive region's blast radius consciously, or use a global IdP (Auth0/Okta) / export-import + token re-login
Terraform state: S3 CRR; Argo CD in each region watching the same Git path with a different overlay (k8s/overlays/prod-sg)
```
**4. Deep dive: failover procedure and split brain.** Trigger = Route 53 health check fails 3×30 s (automated DNS flip, TTL 60 s) but **promotion is a human decision** (runbook): (1) confirm region-wide outage (AWS Health, two independent checks); (2) stop writers in primary if reachable (scale to 0 via Argo CD param) — prevents split brain; (3) `promote-read-replica` (2–5 min, RPO = replica lag, usually < 10 s); (4) point the SG overlay ConfigMap at the promoted endpoint (or pre-create a Route 53 CNAME `db.internal` with low TTL); (5) scale the SG cluster; (6) start MSK consumers from translated offsets; (7) flip DNS if not already. Fail-back is the hard part: the old primary has writes after the flip only if step 2 failed → reconcile with the outbox table (events are the audit log) or accept re-seeding the old region from the new primary (`terraform apply` + snapshot restore).

**5. Trade-offs.** Active-active (Aurora Global Database write forwarding, DynamoDB global tables) doubles complexity: conflict resolution, idempotency across regions, Kafka double-write; for an order system "active-passive with 1-min RPO" is honest and cheap. Cost: ~+40% (replica DB, small cluster, replication traffic $0.02/GB).

### A6. Design a log pipeline (500 nodes, 30k pods, 5 TB/day, 30-day hot retention, 1 year cold)

**1. Requirements.** Structured JSON (ShopFlow emits ECS JSON via `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), tenant isolation, search p95 < 5 s for last 1 h, no log loss during collector restarts, PII redaction, cost ≤ $X.

**2. Numbers.** 5 TB/day = 58 MB/s average, 3× peak 175 MB/s; 30 days hot = 150 TB (compressed 10:1 → 15 TB); 1 year cold = 1.8 PB raw → 180 TB compressed on S3 Glacier IR (~$0.004/GB → ~$720/mo).

**3. Design.**
```
pod stdout ─▶ node agent DaemonSet (Fluent Bit / Grafana Alloy / Vector; ShopFlow compose uses Alloy) 
   enrich with k8s metadata, redact PII (regex on card numbers), multiline (Java stack traces!), backpressure buffer on disk (2 GB)
 ─▶ Kafka "logs" (buffer, decouples bursts, replayable) ─▶ consumers:
      ├─ Loki (labels: namespace, app, level; NOT high-cardinality orderId) chunks → S3; 30-day retention
      ├─ S3 raw JSON, partitioned by dt=/hour= → Athena/Glue for cold queries; lifecycle to Glacier IR after 30 d
      └─ metrics derivation (error counts) → Prometheus (or rely on Micrometer instead)
Grafana: Loki + Tempo + Prometheus linked by traceId (ShopFlow does this in compose: ECS logs carry trace.id)
```
**4. Deep dive: no loss + multiline + cardinality.** Agent reads files with position checkpoints (survives restart); disk buffer absorbs Kafka unavailability; Kafka `acks=all`. Java stack traces are N lines → multiline parser keyed on `^\d{4}-` or, better, log JSON so one event = one line (ShopFlow already does). Loki index labels must be low-cardinality (≤ 10 values); search by `orderId` happens via LogQL line filters over the chosen stream — fast enough with chunk-level bloom filters. Elasticsearch alternative: full-text index, 3–5× more storage and CPU, better for ad-hoc analytics.

**5. Trade-offs.** Push via Kafka costs brokers but gives replay and fan-out; direct agent→Loki is simpler for < 1 TB/day. Sampling debug logs at the source saves 60%. Compliance: WORM bucket (S3 Object Lock) for audit logs; per-tenant Loki tenants via `X-Scope-OrgID`.

---

## PART B — DEEP JAVA / SPRING (12 questions)

### B1. `ConcurrentHashMap` internals (Java 8+)
- Array of `Node<K,V>` bins; `get()` is **lock-free** (volatile reads of `val`/`next`); `put()` uses **CAS** for an empty bin and `synchronized` on the bin's head node otherwise (fine-grained, no segment locks since Java 8).
- Bin → red-black `TreeBin` at 8 nodes (and table ≥ 64), back to list at 6.
- Resize is **cooperative**: threads that encounter a `ForwardingNode` help transfer bins; `sizeCtl` coordinates. `size()` uses striped `CounterCell`s (like `LongAdder`), so it is an estimate under contention.
- No null keys/values (ambiguity with `get` returning null in a concurrent context). `computeIfAbsent` holds the bin lock while computing — a long or recursive mapping function on the same bin **deadlocks/throws** `IllegalStateException: Recursive update`.
- Iterators are weakly consistent (no `ConcurrentModificationException`).
- ShopFlow: Spring's `simple` cache (`spring.cache.type=simple` when `CACHE_TYPE` unset) is a `ConcurrentHashMap`-backed `ConcurrentMapCache` — per pod, not shared; that is why K8s uses `CACHE_TYPE=redis`.

### B2. Happens-before: what guarantees does the JMM give?
- Program order within a thread; `monitor unlock → subsequent lock` of the same monitor; `volatile write → subsequent volatile read`; `Thread.start()` → actions in the thread; actions in the thread → `join()` returns; transitivity.
- Without a HB edge, a reader may see a stale or **partially constructed** object (the double-checked-locking bug fixed by `volatile`). `final` fields get a freeze guarantee after the constructor.
- Practical: `AtomicX`, `java.util.concurrent` collections and `ExecutorService` submission all establish HB; `@Async`/`CompletableFuture` chains do too. In ShopFlow the counters are Micrometer `Counter`s (built on `LongAdder`/`DoubleAdder`), so incrementing from virtual threads is safe.

### B3. G1 vs ZGC vs Parallel — which for a Spring Boot container?
| | Parallel | G1 (default ≥ 2 CPUs/1792 MB) | ZGC (generational since 21) |
|---|---|---|---|
| Goal | throughput | balanced, pause target 200 ms | sub-ms pauses, huge heaps |
| Pauses | long STW | mostly concurrent, STW evacuation | ~1 ms, concurrent everything (load barriers) |
| Overhead | lowest | moderate | more CPU + memory headroom (~10–15%) |
| When | batch | **web services on ≤ 8 GB** | latency-critical, heaps > 16 GB |
Container gotchas: with `-XX:MaxRAMPercentage=75` (ShopFlow `Dockerfile`) in a 512 Mi limit → 384 MB heap; the JVM picks **Serial GC** if it sees < 2 CPUs or < 1792 MB — ShopFlow pods have no CPU limit and 512 Mi, so you get SerialGC unless you set `-XX:+UseG1GC`. Say that; it is a real tuning point. `-XX:+ExitOnOutOfMemoryError` → crash and let K8s restart instead of limping.

### B4. Class loading: how does a Spring Boot fat jar load classes, and what is a `ClassNotFoundException` vs `NoClassDefFoundError`?
- Delegation: Bootstrap → Platform → App → (Boot) `LaunchedClassLoader` that reads nested `BOOT-INF/lib/*.jar`. ShopFlow's image avoids the fat jar at runtime: `java -Djarmode=tools -jar app.jar extract --layers --launcher` unpacks layers and starts `org.springframework.boot.loader.launch.JarLauncher`, which builds that loader over the exploded dirs (faster start, layer caching).
- Loading → linking (verify, prepare, resolve) → initialisation (static init, lazily on first active use).
- `ClassNotFoundException`: checked, class literally not on the classpath at load time (`Class.forName`). `NoClassDefFoundError`: the class was present at compile time but its **static initialiser failed** or a dependency is missing at runtime — look for an earlier `ExceptionInInitializerError`.
- Same class name from two loaders = two different types (`ClassCastException` "X cannot be cast to X") — plugin systems, Tomcat webapps. Spring DevTools uses a restart classloader for the same reason.

### B5. `@Transactional` + `@KafkaListener`: what are the exact semantics in ShopFlow's consumer?
`OrderEventListener.onOrderEvent` is `@KafkaListener(..., groupId="notification-service") @Transactional`, with `enable-auto-commit: false` and `isolation.level: read_committed`.
- Two transactions exist: **DB** (Spring `@Transactional` → `JpaTransactionManager`) and **Kafka offset commit** (done by the listener container *after* the method returns, `AckMode.BATCH` default). They are **not atomic**: commit DB → crash before offset commit → redelivery → the `processedEvents.existsById(eventId)` check against the `processed_event` table makes it a no-op (`notifications.duplicates` counter). Crash *before* DB commit → nothing persisted, redelivery, normal processing. This is at-least-once + idempotent consumer = effectively-once.
- Exceptions: a thrown exception rolls back the DB and the container's `DefaultErrorHandler` (`KafkaErrorConfig`) retries with `ExponentialBackOff(500 ms ×2, max 10 s)` **blocking the partition**, then `DeadLetterPublishingRecoverer` sends to `orders.events.DLT` and commits the offset. Important: `@Transactional` default rolls back only on `RuntimeException`/`Error`; the method `throws Exception` for Jackson, and `JsonProcessingException` is checked → **it would commit** (nothing to commit anyway since it fails on the first line) and the error handler still handles it. Mention `rollbackFor = Exception.class` as hygiene.
- `read_committed` only matters if producers use Kafka transactions; ShopFlow's producer is idempotent but non-transactional, so it is harmless; it protects against future transactional producers.
- Proxy caveat: `@Transactional` is applied via the Spring AOP proxy; the Kafka container invokes the proxied bean, so it works. Self-invocation would not — exactly why `OrderService.saveWithEvent` uses `TransactionTemplate`.

### B6. Why `RestClient` over `RestTemplate` / `WebClient`?
- `RestTemplate`: maintenance mode, template-method API, blocking. `WebClient`: reactive, needs WebFlux on the classpath and a reactive mindset; blocking `.block()` in MVC wastes it. `RestClient` (Spring 6.1): fluent, synchronous, same `ClientHttpRequestFactory` infra, works with virtual threads, supports `onStatus` handlers and `@HttpExchange` interfaces.
- ShopFlow `InventoryClient`: built from the auto-configured `RestClient.Builder` (so Micrometer observations + W3C trace propagation come free), `SimpleClientHttpRequestFactory` with connect 1 s / read 2 s, `onStatus(409 → InventoryRejectedException)` so a business "no" is not retried, `@Retry` + `@CircuitBreaker` around it. Follow-up trap: `SimpleClientHttpRequestFactory` has **no connection pool**; for high RPS switch to `JdkClientHttpRequestFactory` or Apache HttpClient 5 with a pool (`maxTotal`, `defaultMaxPerRoute`) — say it before they ask.

### B7. Virtual threads: pinning, when they do not help, and how to detect it
- A virtual thread mounts on a carrier (ForkJoinPool); on blocking IO the JDK **unmounts** it. **Pinning** happens when blocking inside `synchronized` or a native frame → the carrier is stuck. Java 24 (JEP 491) removes the `synchronized` pinning; on **Java 21 (ShopFlow)** it exists.
- Where it bites Spring apps: JDBC drivers using `synchronized` (older PgJDBC had some; 42.6+ mostly fine), `Object.wait()`, old connection pools. HikariCP uses locks (`java.util.concurrent`), OK.
- They do not help CPU-bound work and do not fix a DB pool that is too small — now 10k virtual threads queue on 5 Hikari connections: Hikari `connectionTimeout` (30 s) becomes the bottleneck → set it low (e.g. 2 s) and surface a 503 fast. In ShopFlow `spring.threads.virtual.enabled=true` + `DB_POOL_SIZE=5`; the pool is the throttle by design.
- Detect: `-Djdk.tracePinnedThreads=full` (21), JFR event `jdk.VirtualThreadPinned`, thread dumps via `jcmd <pid> Thread.dump_to_file -format=json`. ThreadLocals: fine but expensive at scale — use `ScopedValue`.

### B8. Hibernate flush order and why the `release()` method in ShopFlow is written in that order
Hibernate flushes in a fixed **ActionQueue** order: inserts → updates → collection removals/updates/inserts → deletes, regardless of your code order; native/JPQL `@Modifying` queries trigger `flushAutomatically` first (flush mode AUTO) and `clearAutomatically` clears the persistence context afterwards. In `ReservationService.release`: `r.release()` (dirty entity) then `productRepository.incrementStock()` with `@Modifying(flushAutomatically = true, clearAutomatically = true)` → the update of the reservation is flushed **before** the bulk update, then the context is cleared so `r` becomes detached — if the order were reversed, `r.release()` after the clear would be a change to a detached object and silently lost. The decrement in `reserve` is a bulk JPQL update bypassing the first-level cache, so the stale `Product` entity (if loaded) is cleared — that is what `clearAutomatically` is for. Bonus: `FlushMode.COMMIT` vs `AUTO`, `@DynamicUpdate`, and the fact that `@Version` checks happen at flush → `OptimisticLockException` mapped to 409 in `GlobalExceptionHandler`.

### B9. Connection pool sizing: the formula and ShopFlow's numbers
- HikariCP guidance: `connections = (core_count × 2) + effective_spindle_count`; for a 2-vCPU `db.t4g.medium` ≈ 5–10 *active* connections saturate it. More connections → context switching and lock contention, lower throughput.
- Little's law for the pool: `needed = throughput × avg_hold_time`. 200 req/s × 20 ms = 4 connections. ShopFlow: `DB_POOL_SIZE=5` per pod, max 10 pods × 3 services = 150 ≤ `max_connections=300` (`rds.tf`). Postgres cost ≈ 5–10 MB RAM per backend.
- Why not one `@Transactional` across the HTTP call: a connection would be held for connect 1 s + read 2 s × 3 retries ≈ up to 9 s → 5 connections serve < 1 req/s. `OrderService` is deliberately not transactional across `inventoryClient.reserve` (`OrderService.java` javadoc). Set `connectionTimeout` 2–5 s, `maxLifetime` < DB/proxy idle timeout, `leakDetectionThreshold` 10 s in staging.

### B10. Spring Boot auto-configuration ordering and overriding a bean cleanly
`@SpringBootApplication` → `@EnableAutoConfiguration` → `AutoConfigurationImportSelector` reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, applies `@ConditionalOnClass/OnMissingBean/OnProperty`, ordered by `@AutoConfigureBefore/After`. User `@Configuration` is processed first, so your bean wins `@ConditionalOnMissingBean`. ShopFlow: `KafkaErrorConfig` defines a `DefaultErrorHandler` bean; Spring Kafka's auto-config picks up a `CommonErrorHandler` bean for the listener container factory — overriding without redefining the factory. `JwtRolesConverter` is injected into `oauth2ResourceServer().jwt().jwtAuthenticationConverter` rather than relying on defaults. Debug: `--debug` prints the CONDITIONS EVALUATION REPORT; `spring.main.allow-bean-definition-overriding` is a smell.

### B11. `@Transactional` propagation traps interviewers love
`REQUIRED` joins; `REQUIRES_NEW` suspends and opens a 2nd connection (pool deadlock risk if pool = 1); `NESTED` = savepoint (JDBC only); a `RuntimeException` caught *inside* the inner method still marks the shared tx **rollback-only** → `UnexpectedRollbackException` at the outer commit; checked exceptions do not roll back by default; `readOnly=true` is a hint (Hibernate skips dirty checking → measurable speedup, used in `ReservationService.get`). The relay in `OutboxRelay.publishPending` is `@Transactional` to hold `FOR UPDATE SKIP LOCKED` rows across the batch — several pods poll concurrently without double publishing (`OutboxRepository.findUnpublished`, lock timeout `-2` = SKIP LOCKED on PostgreSQL).

### B12. Memory: why does a container with 512 Mi get OOMKilled when the heap is 384 MB?
Heap is only part of RSS: Metaspace (~60–100 MB for Spring), thread stacks (1 MB each × platform threads — virtual threads help), code cache (~50 MB), GC data structures, direct `ByteBuffer`s (Netty/Kafka client!), JIT, glibc arenas. `MaxRAMPercentage=75` leaves 128 MB for all of that; with Kafka producer buffers (`buffer.memory` 32 MB default) it is tight. Tools: `jcmd <pid> VM.native_memory summary` (`-XX:NativeMemoryTracking=summary`), `kubectl top`, `container_memory_working_set_bytes`. Fixes: lower to 60–65%, `MALLOC_ARENA_MAX=2`, set `-Xss`, cap direct memory. Exit code 137 + `OOMKilled` in `kubectl describe` is the signature (vs `ExitOnOutOfMemoryError` → exit 3, a *heap* OOM, different problem).

---

## PART C — DEEP KUBERNETES (10 questions)

### C1. What happens on `kubectl apply -f deployment.yaml`, end to end?
```
kubectl: load kubeconfig → build request → client-side apply computes 3-way diff (last-applied annotation) or SSA (--server-side; Argo CD uses ServerSideApply=true in application-prod.yaml)
 → HTTPS to kube-apiserver
apiserver: authn (cert/OIDC/token; EKS: IAM via aws-iam-authenticator + access entries) → authz (RBAC) → mutating admission (webhooks: EKS pod-identity webhook injects IRSA env/volume; defaults) → schema validation → validating admission (webhooks, ValidatingAdmissionPolicy/CEL) → persist to etcd (Raft, quorum write, resourceVersion++) 
 → watch event to controllers
deployment-controller: creates/updates ReplicaSet (pod-template-hash) ; rolls per strategy (ShopFlow maxSurge 1, maxUnavailable 0)
replicaset-controller: creates Pods (no nodeName)
scheduler: watches unscheduled pods → filter (resources: requests 250m/384Mi, taints, nodeSelector, topologySpreadConstraints maxSkew 1 on hostname) → score → binds pod.spec.nodeName
kubelet on that node: watches its pods → pulls image (ECR via node role) → CRI (containerd) creates sandbox → CNI (VPC CNI) assigns an ENI secondary IP → runs init/containers → probes (startup 2s×30, liveness, readiness) → updates Pod status
endpointslice-controller: Ready pod IP → EndpointSlice → kube-proxy / LB controller target group registration
HPA controller: every 15 s reads metrics-server → scales ReplicaSet via Deployment /scale
```
Interview add-on: "declarative = I write desired state; controllers reconcile; nothing calls anything directly, everything watches etcd through the apiserver".

### C2. How does a Service ClusterIP actually route? iptables vs IPVS vs eBPF
- ClusterIP is a **virtual IP**: no interface owns it. `kube-proxy` watches Services/EndpointSlices and programs the node.
- **iptables mode**: `KUBE-SERVICES` chain → per-service `KUBE-SVC-xxx` → random `statistic --probability` jumps to `KUBE-SEP-xxx` → DNAT to pod IP:port; conntrack keeps the flow. O(n) rule evaluation, slow updates at > 5k services. Pods on the same node use the same path.
- **IPVS mode**: kernel L4 load balancer with a hash table, O(1), real algorithms (rr, lc, sh), still uses iptables for a few things.
- **eBPF (Cilium, Calico eBPF)**: replaces kube-proxy; socket-level LB (`connect()` is rewritten so the packet never carries the ClusterIP), DSR for NodePort, identity-based NetworkPolicy, Hubble observability. EKS: `kube-proxy` add-on in iptables mode by default; Cilium can be installed in kube-proxy replacement mode.
- DNS: `inventory-service` → CoreDNS → `inventory-service.shopflow.svc.cluster.local` → ClusterIP. ShopFlow `INVENTORY_URL=http://inventory-service:8082`. Headless services (`clusterIP: None`) return pod IPs — StatefulSets (the dev Postgres/Kafka) use them.

### C3. Why do CPU limits throttle even when the node is idle, and why does ShopFlow set none?
CFS bandwidth control: limit `500m` = 50 ms of CPU per 100 ms period (`cpu.cfs_quota_us/period_us`). A multi-threaded JVM (GC threads, JIT, Tomcat) can burn the 50 ms in 20 ms wall time and then **sleep 80 ms** → latency spikes, slow start-up, even though the node is 70% idle. Metric: `container_cpu_cfs_throttled_periods_total / container_cpu_cfs_periods_total`. Requests, not limits, drive scheduling and the HPA (`averageUtilization: 70` is % of **request**). ShopFlow's `deployment.yaml` sets `requests.cpu: 250m` and intentionally **no CPU limit** (comment in the file), while keeping a **memory limit** (memory is not compressible: OOMKill is the only enforcement). Trade-off: a noisy pod can steal CPU → use requests sized honestly + `topologySpreadConstraints`, or set a high limit (4×) if the platform team insists. QoS class becomes Burstable (not Guaranteed) — accept it.

### C4. PDB vs eviction vs preemption vs node-pressure eviction — who wins?
| Mechanism | Trigger | Respects PDB? |
|---|---|---|
| Voluntary eviction (Eviction API: `kubectl drain`, cluster autoscaler, Karpenter consolidation, EKS node group rolling update) | drain | **Yes** — blocked while it would violate `minAvailable` |
| Scheduler **preemption** | a higher-priority pod cannot schedule | PDB is "best effort" — it tries but may violate |
| **Node-pressure eviction** (kubelet: memory/disk) | node resources low | **No** — kills BestEffort first, then Burstable over request, Guaranteed last |
| OOMKill | cgroup memory limit | No (not even an eviction) |
| Deleting the pod / rolling update | user/controller | No — PDB only covers the Eviction API |
ShopFlow: `pdb.yaml` `minAvailable: 1` per service, HPA `minReplicas` 3 in prod → drains can always proceed; in dev the overlay switches to `maxUnavailable: 1` because a single replica with `minAvailable: 1` would **block drains forever** (classic incident during cluster upgrades). `PriorityClass` for the services and `system-cluster-critical` for CoreDNS.

### C5. StatefulSet ordering guarantees — what is and is not guaranteed
Guaranteed: stable identity `name-0..N-1`, stable DNS via headless Service, one PVC per ordinal (`volumeClaimTemplates`) that survives pod deletion, **ordered** creation 0→N-1 (each must be Running+Ready before the next, `podManagementPolicy: OrderedReady`) and reverse-ordered termination, rolling update from N-1 down to 0 with `partition` for canaries. Not guaranteed: that pod-0 is the leader (that is the app's job: Kafka KRaft quorum, Postgres primary election via Patroni), that a pod is rescheduled quickly when its node dies (StatefulSet pods are **not** force-replaced on an unreachable node to avoid two writers on the same PVC; needs manual `--force` or a node fencing operator), data consistency across replicas. ShopFlow: dev-only `postgres` and `kafka` StatefulSets (`k8s/base/postgres/statefulset.yaml`) — the prod overlay **deletes** them (`$patch: delete`) in favour of RDS/MSK, precisely because running stateful quorum systems on K8s correctly is an operational burden the project does not want.

### C6. kube-proxy vs Cilium: why would you switch and what breaks?
Switch for: NetworkPolicy enforcement (vanilla VPC CNI needs the network policy agent enabled or Calico — the base `network-policies.yaml` says so), L7 policies (HTTP path-based), identity instead of IP-based rules (survives pod IP churn), Hubble flow logs (debugging "pod can't reach RDS" in minutes), kube-proxy replacement performance, transparent encryption (WireGuard). What breaks/changes: rules in `ipBlock` for AWS services (prod overlay egress to `10.0.0.0/16`) still work but Cilium adds `toFQDNs` so you could allow `*.rds.amazonaws.com` instead of a CIDR; host-network pods and `hostPort` semantics; some apps relying on seeing the ClusterIP; the EKS VPC CNI can run in chaining mode or Cilium ENI mode (IPAM change = re-roll all pods). Migration path: install alongside, migrate node pools.

### C7. etcd quorum, what 3 vs 5 nodes buy, and what happens when quorum is lost
Raft: a write commits when a majority (⌊n/2⌋+1) persist it. 3 nodes tolerate 1 failure, 5 tolerate 2; even numbers add nothing. Lose quorum → etcd becomes **read-only** (stale reads allowed with `--consistency=s`), apiserver writes fail, controllers cannot reconcile, **running pods keep running** (kubelet is autonomous) but no scaling, no new pods, no Service endpoint updates. Recovery: restore a snapshot (`etcdctl snapshot restore`) → cluster ID changes → all members rejoin. Limits: 8 GB db size (defrag, compaction), keep objects small (ConfigMaps of 1 MB), latency-sensitive (fsync < 10 ms → SSD). On EKS etcd is AWS-managed and encrypted (KMS envelope encryption for Secrets optional in `eks.tf` via `encryption_config`); ShopFlow relies on ESO so only the synced `shopflow-db` Secret ever sits in etcd.

### C8. The graceful-termination race: why do rolling updates produce 502s and how does ShopFlow avoid them?
```
t0 pod marked Terminating (deletionTimestamp)  ──┬─▶ kubelet: run preStop hook, then SIGTERM, wait terminationGracePeriodSeconds (45 s), SIGKILL
                                                 └─▶ endpoint controller removes the IP from EndpointSlices → kube-proxy/ingress/ALB update (takes 1–5 s, async, best effort)
```
Both arrows start at the same time. If the app closes its listener on SIGTERM immediately, requests still routed to it for those seconds get connection refused → 502. ShopFlow's fixes in `deployment.yaml` + `application.yml`: `preStop: sleep 5` (keep serving while routers converge), `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s` (stop accepting, finish in-flight, then stop Kafka listener and Hikari), `terminationGracePeriodSeconds: 45` ≥ 5 + 20 + margin, `maxUnavailable: 0` so a new pod is Ready before an old one dies, readiness probe so the new pod receives traffic only when `db` is UP. On AWS ALB add pod readiness gates and `alb.ingress.kubernetes.io/target-group-attributes: deregistration_delay.timeout_seconds=30`. Kafka consumer: graceful shutdown lets the current batch finish and commit, else redelivery (safe, idempotent).

### C9. HPA maths and why scaling is "slow"
`desired = ceil(current × (currentMetric / target))`; e.g. 3 pods at 90% of a 70% target → `ceil(3 × 1.29) = 4`. Sync every 15 s on metrics-server data (~60 s window), tolerance 10%, scale-up unlimited by default but scale-down waits `stabilizationWindowSeconds: 300` (ShopFlow). JVM start ≈ 20–40 s + startupProbe → a new pod helps after ~60 s; so a 10-s flash sale needs pre-scaling (KEDA cron) or over-provisioning. HPA on CPU alone is blind to IO-bound saturation → use KEDA with Kafka lag (`notification-service`) or Prometheus RPS. HPA vs VPA conflict on the same metric; cluster autoscaler/Karpenter adds nodes when pods are Pending (another ~60–90 s on EC2).

### C10. Image pull, secrets and supply chain in the cluster
`imagePullPolicy`: `IfNotPresent` default for tagged images, `Always` for `:latest`; with immutable SHA tags `IfNotPresent` is safe and fast. Verify signatures (cosign + Kyverno/Sigstore policy controller) — not in ShopFlow yet. Pull from ECR uses the node role (no `imagePullSecrets`); from GHCR a Secret of type `kubernetes.io/dockerconfigjson` is needed for private packages. Pod security: `runAsNonRoot` + fixed UID 10001 (matches the Dockerfile's `adduser -u 10001`), `readOnlyRootFilesystem` with an `emptyDir` on `/tmp` for Tomcat, `drop ALL` capabilities, `seccompProfile RuntimeDefault`, `automountServiceAccountToken: false`. Pod Security Admission `restricted` on the namespace would pass with these.

---

## PART D — DISTRIBUTED SYSTEMS (10 questions)

### D1. "Exactly-once" is a myth — or is it?
Exactly-once *delivery* is impossible over a lossy network (Two Generals): a sender cannot know if the receiver processed a message without an ack, and the ack can be lost. What exists is **exactly-once processing (effectively-once)** = at-least-once delivery + idempotent (or transactional) processing. Kafka's "EOS" is exactly this scoped to Kafka→Kafka: idempotent producer (sequence numbers per partition dedupe retries) + transactions (atomic write of records + offsets, `read_committed` readers). The moment a side effect leaves Kafka (DB write, email), you are back to idempotency. ShopFlow: producer `enable.idempotence=true` + `acks=all`; consumer dedup table `processed_event` keyed by `eventId` (UUID generated once when the outbox row is created). So: "at-least-once everywhere, idempotent at every boundary".

### D2. Outbox vs CDC vs 2PC for "save order and publish event"
| | 2PC/XA | Transactional outbox (polling) | CDC (Debezium) |
|---|---|---|---|
| Atomicity | true across DB + broker | DB-local; relay is at-least-once | DB-local; log tailing |
| Availability | coordinator = SPOF, blocking locks | no coupling at write time | no coupling |
| Latency | high | poll interval (ShopFlow 1 s) | ms |
| Ops | XA drivers, Kafka doesn't support XA | a table + a scheduled job | Kafka Connect cluster, DB replication slot |
| Load on DB | locks | polling query (indexed on `published_at`), `SKIP LOCKED` | WAL reader, replication slot can bloat WAL if the connector stops |
ShopFlow: `OrderService.saveWithEvent` writes `orders` + `outbox_event` in one `TransactionTemplate` transaction; `OutboxRelay` publishes with key `aggregateId` (orderRef → per-order ordering), waits for the ack (`.get(5 s)`), marks published, stops at first failure to preserve order; `outbox.unpublished` gauge → `OutboxBacklogGrowing` alert; published rows are deleted after 7 days by `OutboxRelay.cleanup` (daily cron, `deletePublishedBefore`), unpublished never. CDC would remove the poll and the duplicate-on-crash window but adds Debezium ops; at ShopFlow's scale polling wins. 2PC is rejected because Kafka has no XA and because of blocking (Q: "in `OrderService` why no `@Transactional`?" → see B9).

### D3. Where do you store idempotency keys and for how long?
Options: the business table itself (ShopFlow: `orders.idempotency_key UNIQUE` — zero extra infra, retention = forever, replay returns the real row); a dedicated table with TTL partitioning (daily partitions, drop old); Redis with TTL (fast, but a Redis loss turns retries into duplicates — acceptable for some APIs, not payments); DynamoDB with TTL attribute (serverless, strong consistency on PK). Key design: `tenant:clientKey` to isolate tenants; store request fingerprint + response; TTL 24 h–7 d per API contract; make the write and the business write **one transaction** or you reintroduce the race. Consumers: `processed_event(event_id)` grows forever in ShopFlow → add a monthly cleanup job after Kafka retention (7 days) has passed; nothing older can be redelivered.

### D4. Kafka rebalance storms: cause, effect, fixes
Cause: a consumer leaves/joins or **appears dead** (missed `session.timeout.ms` heartbeats, or `poll()` not called within `max.poll.interval.ms` = 5 min default because processing a batch took too long) → group coordinator triggers rebalance → with the eager protocol **all** partitions are revoked and reassigned → a stop-the-world pause; the slow consumer rejoins → another rebalance → storm. Effects: lag spikes, duplicate processing (uncommitted offsets), `CommitFailedException`. Fixes: `CooperativeStickyAssignor` (incremental, only moved partitions pause); `max.poll.records` down (ShopFlow default 500 → 50 if each record calls SES); raise `max.poll.interval.ms`; **static membership** (`group.instance.id` = pod name via StatefulSet or Downward API) so a restart within `session.timeout.ms` does not trigger a rebalance; fix the slow path (async DB writes, batching); readiness so HPA doesn't flap pods (ShopFlow `scaleDown.stabilizationWindowSeconds: 300`). Observe: `kafka_consumer_coordinator_rebalance_total` and `kafka_consumer_fetch_manager_records_lag_max` (the `KafkaConsumerLagHigh` alert in `alert-rules.yml`).

### D5. Consumer lag: how to measure correctly and what to do about it
Lag = `log end offset − committed offset` per partition. Measure broker-side (consumer group command, MSK CloudWatch `SumOffsetLag`, Burrow) **and** client-side (`records-lag-max`); time-lag (`EstimatedMaxTimeLag`) is what users feel. Growth rate matters more than absolute value: `deriv(lag[5m]) > 0 for 10m` = you will never catch up. Causes: slow consumer (DB, external API), partition skew (hot key — `orderRef` is a UUID so even), too few partitions for pods (ShopFlow 6 partitions → > 6 notification pods idle), rebalances (D4), producer burst. Remedies: scale consumers up to partition count; increase partitions (breaks key→partition mapping for existing keys — ordering per key is lost across the boundary; do it at a quiet time); batch processing; parallel processing within a partition with ordering per key (Confluent parallel consumer); skip/park poison records (DLT). KEDA ScaledObject on lag is the auto-scaling answer.

### D6. Clock skew: where it breaks systems and how to design around it
Wall clocks drift (ms–s across nodes; VMs after pause can jump). Breaks: JWT `exp`/`nbf` validation (Spring allows 60 s skew by default; Cognito tokens are 15 min, so a 5-min skew = 1/3 of the lifetime), TTL-based leases (a node thinks it still holds a lock), ordering by timestamp (last-write-wins conflicts), certificate validity, Kafka `CreateTime` vs `LogAppendTime` for retention, distributed tracing spans starting before their parent. Design: use **monotonic** clocks for durations (`System.nanoTime()`), **logical clocks** (Lamport/vector) or **sequence numbers** for ordering (ShopFlow orders events by DB `created_at` + outbox insertion order + Kafka offset, never by consumer clock), fencing tokens for leases, TrueTime/HLC in Spanner/CockroachDB, NTP/chrony with monitoring (`node_timex_offset_seconds` alert > 100 ms), AWS Time Sync Service on EKS nodes.

### D7. CAP vs PACELC — apply it to ShopFlow's components
CAP: under a **P**artition choose **C** (refuse writes) or **A** (accept divergent writes). PACELC adds: **E**lse (no partition) trade **L**atency vs **C**onsistency. ShopFlow: PostgreSQL single primary = CP (Multi-AZ sync replication → failover not availability); Kafka with `acks=all`, `min.insync.replicas=2` = CP for producers (PC/EC) — two brokers down → writes rejected; Redis cache = AP-ish (async replica, possible stale read after failover, acceptable because TTL 60 s + eviction on write); Cognito = AP for token validation (JWKS cached, validation is offline). The order flow is **eventually consistent** across services (saga with statuses PENDING→CONFIRMED/REJECTED/FAILED), strongly consistent within each DB. Say: "CAP is about a partition; most of the time I'm trading latency, e.g. `acks=all` costs ~5 ms per publish."

### D8. A saga fails in the middle — walk through ShopFlow's order saga
Steps: (1) save `PENDING` order; (2) HTTP `reserve` on inventory; (3) save `CONFIRMED` + outbox; (4) relay → Kafka; (5) notification. Failure points: after (1) before (2): order stays PENDING; a sweeper should fail/expire PENDING orders older than N minutes (**not in ShopFlow** — honest gap). (2) timed out but inventory *did* reserve → retry is idempotent by `orderRef`; if all retries fail the order is `FAILED` (`OrderService` catch block) and the stock may stay reserved → handled by the **reconciliation job** `OrderReconciler` (`@Scheduled` every 60s): FAILED orders older than a 2-minute grace get `release(orderRef)` (idempotent no-op if nothing was reserved) and become CANCELLED with an `OrderCancelled` event; the batch stops while inventory is still down. Lesson: every saga needs a timeout path that converges to a definite state, and compensation must be idempotent so the job can be dumb. (3) DB down → exception → client gets 500, retries with the same `Idempotency-Key` → finds the PENDING order... current code returns it as-is (`created=false`) → a second honest gap: replay should resume the saga, not return a stale PENDING. (4) Kafka down → outbox backlog, alert, no loss. (5) notification crash → redelivery + dedup. Compensation = `cancel()` → `release()` → `OrderCancelled` event. Choreography vs orchestration: ShopFlow is orchestrated by order-service (simple, 2 participants); with 4+ steps use a state machine table or Temporal/Step Functions. Semantic lock: a reservation is a "pending" hold, not a final decrement — the `Reservation` row with status is exactly that.

### D9. Leader election: how, and when you don't need it
Approaches: ZooKeeper/etcd ephemeral node + watch; Kubernetes `Lease` object (client-go `leaderelection`, used by controllers; renew every 2 s, lease 15 s); DB row lock (`SELECT ... FOR UPDATE` on a singleton row, held by the leader's transaction — simple, uses existing infra); Redis Redlock (controversial without fencing). Always include a **fencing token** (monotonic epoch) on writes so a paused ex-leader is rejected. ShopFlow deliberately **avoids** election for the outbox relay: all pods run `publishPending` and `FOR UPDATE SKIP LOCKED` partitions the work — parallel, no SPOF, no lease; the cost is possible out-of-order publishing across pods (per-order ordering survives because an order's events are rare and a pod stops at the first failure). For "must be exactly one" (e.g. a sweeper that sends daily emails) use ShedLock (`@SchedulerLock` with a JDBC lock table) or a K8s CronJob with `concurrencyPolicy: Forbid`.

### D10. Back-pressure and bulkheads across HTTP → Kafka → DB
Every boundary needs a bounded queue and a timeout, else one slow component fills memory upstream. ShopFlow's chain: rate limiter on `POST /orders` (50/s/pod, fail fast 429) → Hikari pool 5 (bounded, `connectionTimeout`) → `RestClient` timeouts 1 s/2 s → circuit breaker (10-call window, 50%, open 10 s) → outbox table (unbounded on purpose: durable buffer, alert at 100) → Kafka (broker-side disk, 7 days) → consumer `max.poll.records` + DLT. Missing: a bulkhead (separate thread/connection pool) between "place order" and "list orders" so a slow inventory never starves reads — Resilience4j `@Bulkhead` or a second datasource. With virtual threads the bulkhead is the DB pool, so consider two Hikari pools. Load shedding at the ALB with WAF rate rules completes the picture.

---

## PART E — DEVOPS / SRE (12 questions)

### E1. Blue-green deployment with a database schema change — how?
Rule: **expand → migrate → contract**, schema changes are separate deployments from code changes, every migration must be compatible with the version N-1 that is still running (blue) and N (green). Example: rename `orders.sku` → `product_sku`: (1) deploy migration adding `product_sku` nullable + trigger/dual-write in code (Flyway `V2__add_product_sku.sql`; ShopFlow runs Flyway at app start with `ddl-auto: validate`, so the migration ships with green and must not break blue → `validate` on blue still passes because columns were only added); (2) backfill in batches (`UPDATE ... WHERE id BETWEEN` to avoid a long lock); (3) switch reads to the new column (next release); (4) stop writing the old column; (5) drop it (`V5`, weeks later). Never: `ALTER TYPE`/`NOT NULL` on a big table without `NOT VALID` + `VALIDATE`, index creation without `CONCURRENTLY` (Flyway: `-- flyway:executeInTransaction=false`). Blue-green on K8s: two Deployments + Service selector flip or Argo Rollouts `strategy.blueGreen` with `prePromotionAnalysis`. Rollback = flip back; the DB stays forward-compatible so no data rollback is needed — that is the whole point of expand/contract. Risk management: run the migration as a separate Job/init before green starts so a failing migration never leaves half-started pods.

### E2. Canary analysis: which metrics, how long, how to decide automatically?
Golden signals compared **canary vs baseline (a fresh copy of stable, not the whole old fleet)**: error rate (`http_server_requests_seconds_count{status=~"5.."}`), p50/p99 latency (`histogram_quantile` over `_bucket`), saturation (CPU, Hikari active/pending `hikaricp_connections_pending`), business metrics (`orders_total{status="CONFIRMED"}` rate, `inventory_reservations_rejected_total`, `outbox_unpublished`), logs error ratio, JVM (GC pause, heap after GC). Steps 1% → 5% → 25% → 50% → 100%, each ≥ 5–10 min (one HPA cycle + enough samples: at 10 RPS × 5% = 0.5 RPS → 300 requests in 10 min, barely enough; at low traffic prefer longer steps or synthetic load). Decision: Argo Rollouts `AnalysisTemplate` with Prometheus queries and thresholds (`successCondition: result[0] < 0.01`), fail → auto-rollback; Kayenta-style statistical comparison (Mann-Whitney) for mature setups. ShopFlow has the metrics and alert rules (`monitoring/alert-rules.yml`) but plain RollingUpdate; the next step is Argo Rollouts with the ALB controller doing weighted target groups (`alb.ingress.kubernetes.io/actions`).

### E3. First 5 minutes of a Sev-1 (checkout down) — what exactly do you do?
0:00 acknowledge the page, open the incident channel, declare Sev-1, name roles (IC = you until relieved, comms, ops). 0:30 **impact first**: error-rate dashboard by service, `up`, ALB 5xx — is it all users or a slice? Post a first status: "investigating, checkout errors since 10:42". 1:00 **what changed?** Argo CD history (`argocd app history shopflow-prod`), last `deploy(prod): pin images` commit, Terraform applies, AWS Health dashboard, RDS events, MSK alarms. 2:00 **mitigate, don't diagnose**: if a deploy correlates → `argocd app rollback` / `git revert` (ECR immutable tags make the previous SHA exact); if RDS failover → wait it out, scale nothing; if circuit breaker open (`InventoryCircuitOpen`) → inventory-service is the cause, go there; if `OutboxBacklogGrowing` → Kafka, orders still accepted, downgrade severity. 3:30 check blast radius: are we losing data or only availability? (Outbox + idempotency keys mean retries are safe → tell the frontend to retry.) 4:30 second status update with ETA; hand off diagnosis to a second engineer; keep a timeline in the channel for the postmortem. Principle: **restore service before finding root cause**; never debug in prod for 30 min while customers wait.

### E4. Define an SLO for checkout and the SLIs behind it
SLIs (measured at the ALB/edge, not inside the pod): availability = `1 − 5xx / total` on `POST /api/v1/orders`; latency = share of requests < 500 ms; correctness = orders with status CONFIRMED or REJECTED (a business "no" counts as success!) vs FAILED. SLO: 99.9% availability over 30 days rolling (error budget 43.2 min), 99% < 500 ms. Why 99.9 and not 99.99: Multi-AZ failover alone eats 1–2 min, a monthly deploy incident eats more; 99.99 (4.3 min/month) is unaffordable without RDS Proxy and multi-region. Exclusions: client 4xx (including 429 from the rate limiter — debatable; track it separately), planned maintenance only if communicated. PromQL from ShopFlow: `sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",status=~"5.."}[30d])) / sum(rate(...[30d]))`. Burn-rate alerts: page at 14.4× over 1 h (2% budget in 1 h) and 6× over 6 h; ticket at 1× over 3 days — replaces the static `> 5% for 5m` rule in `alert-rules.yml` for mature teams.

### E5. Error budget policy — what happens when it is spent?
Written agreement signed by product + engineering: budget remaining > 50% → ship freely; 10–50% → every deploy needs a canary + rollback plan; **exhausted** → feature freeze, only reliability work and security fixes, until the 30-day window recovers; repeated exhaustion → re-negotiate the SLO or the architecture (e.g. RDS Proxy, multi-region). Exceptions require VP sign-off and are logged. Why it works: it turns "reliability vs features" from a fight into arithmetic and gives developers a reason to care about the pager. Also define what consumes the budget: incidents, failed deploys, *and* planned risky changes (EKS upgrade = expect to burn ~2 min).

### E6. Postmortem culture — how do you run one and what does "blameless" really mean?
Within 48 h, written doc: timeline (from the incident channel), impact (users, minutes, orders FAILED — query `orders_total{status="FAILED"}`), root cause**s** (plural; use 5 Whys but stop at systemic causes, not "the engineer typed wrong"), what went well, what went badly, action items with owners and due dates tracked as tickets, and "how did we get lucky". Blameless = assume everyone acted reasonably with the information they had; ask "why did the system let this happen?" not "who". Metrics: TTD (time to detect — did the alert page or a customer?), TTM (to mitigate), TTR. Example from ShopFlow-style setups: "deploy at 17:50 changed `DB_POOL_SIZE` 5→20 across 10 pods → Postgres hit `max_connections` (dev compose = 200) → `FATAL: too many connections` → TTD 4 min via `HighErrorRate`, TTM 6 min via `git revert`. Actions: alert on `DatabaseConnections > 80%`, put the pool-size × pods ≤ max_connections check in CI (a kubeconform-style policy), add RDS Proxy." Publish company-wide; the best teams read others' postmortems monthly.

### E7. Rotate database credentials without downtime
Prerequisite: two valid credentials at once (**alternating users** `orders_a`/`orders_b`, or Postgres `ALTER USER ... VALID UNTIL` with a grace period; Secrets Manager's RDS rotation Lambda implements alternating users). Flow in ShopFlow terms: rotation creates the new password for the inactive user and flips the "current" key in `shopflow/prod/db` → External Secrets refreshes the K8s Secret `shopflow-db` (`refreshInterval: 1h`, or force with the `force-sync` annotation) → pods must pick it up: Spring reads env at start, so **Reloader** (stakater) watches the Secret and triggers a rolling restart (`maxUnavailable: 0`, readiness-gated → zero downtime); existing Hikari connections stay authenticated until `maxLifetime` (30 min default) recycles them — fine because the old password is still valid for the grace window. Alternative without restarts: mount the secret as a file and use a Hikari `DataSource` wrapper that re-reads the password (`HikariDataSource.setPassword` affects new connections only). Best: no password at all — **RDS IAM authentication** via IRSA (15-min tokens generated by the SDK) or RDS Proxy with IAM. Verify: `pg_stat_activity` shows connections from the new user; alert on auth failures (`FATAL: password authentication failed` log metric). Same pattern for Kafka SCRAM; IAM auth on MSK already avoids it (`msk.tf`).

### E8. A GitHub Action you use is compromised (supply-chain attack) — impact and defences
Scenario (real: `tj-actions/changed-files` 2025): a tag is moved to a malicious commit that dumps runner memory/secrets to logs. Impact in ShopFlow's workflow: `GITHUB_TOKEN` with `packages: write` (push a poisoned image to GHCR!) and `contents: write` in `deploy-manifests` (commit a malicious image tag that Argo CD would deploy). The OIDC role in `ci-oidc.tf` limits AWS blast radius to ECR push on three repos — but a poisoned image *is* the attack. Defences: **pin actions to a commit SHA** (`uses: actions/checkout@<40-hex>` with Dependabot/Renovate updating; the workflow today pins to major tags `@v7` — say you would change this); least-privilege `permissions:` per job (done); restrict allowed actions in org settings (`allowed_actions: selected`, verified creators); `step-security/harden-runner` egress policy; sign images (cosign keyless with the OIDC identity) and verify in-cluster (Kyverno `verifyImages`) so an image not built by *that* workflow identity cannot run; SLSA provenance attestations (`actions/attest-build-provenance`); branch protection + required reviews so the bot's commit to `main` cannot carry arbitrary changes (the bot should only touch `kustomization.yaml` — enforce with a CODEOWNERS/path rule); immutable ECR tags so the SHA cannot be swapped; audit CI logs for secret exfiltration; rotate `GITHUB_TOKEN` (automatic) and any PAT.

### E9. Terraform in a team: state, drift, and the "someone clicked in the console" problem
Remote S3 state with locking (`versions.tf`), one state per environment/stack (ShopFlow: `prod/shopflow.tfstate`; split VPC/EKS/data into separate states when a plan takes > 5 min or blast radius matters), PRs run `terraform plan` and post it, apply only from CI on `main` with a manual approval environment (the workflow comment in `.github/workflows/shopflow.yml` describes this step), `-detailed-exitcode` nightly drift detection job, `lifecycle { ignore_changes }` for fields other systems own (e.g. `desired_size` when cluster autoscaler scales), `prevent_destroy` on RDS/MSK, `moved {}` blocks for refactors, `import` blocks for click-ops resources instead of recreating, SCPs/IAM denying console writes for humans in prod. Modules pinned (`~> 21.0`), providers locked via `.terraform.lock.hcl` (commit it). Secrets in state → bucket policy + KMS. Testing: `terraform validate`, `tflint`, `checkov`/`trivy config`, `terraform test` for modules.

### E10. GitOps edge cases: Argo CD out-of-sync loops, HPA fights, and secrets
Loops: a mutating webhook or a controller writes fields back → perpetual OutOfSync → `ignoreDifferences` (ShopFlow ignores `/spec/replicas` on Deployments because the HPA owns it) or Server-Side Apply with field managers (`ServerSideApply=true` in `application-prod.yaml` makes Argo own only its fields). `selfHeal` reverts hand edits within seconds — a hotfix must go through Git (or disable auto-sync during an incident, then reconcile). `prune: true` deletes what you remove from Git — guard CRDs/namespaces with `Prune=false` annotations. Secrets: never in Git → ESO; Argo diff hides secret values. Sync waves/hooks for ordering (CRDs before CRs, DB migration Job as PreSync). Multi-cluster: ApplicationSet with cluster generator; app-of-apps. Image updater vs CI commit (ShopFlow: CI commits the SHA; a `GITHUB_TOKEN` push does not retrigger the workflow — avoids the loop).

### E11. Capacity planning for Black Friday (10× normal)
Baseline from metrics: today 20 RPS peak on orders, p99 300 ms, 3 pods at 30% CPU, Hikari 2 active, RDS 15% CPU, Kafka 10 msg/s. Target 200 RPS. Per-pod capacity ≈ 20 RPS ÷ 3 pods ÷ 0.3 ≈ 22 RPS at 100% CPU → aim at 60% → ~15 pods for order-service → `maxReplicas: 10` is too low (patch it), nodes: 15 × 384 Mi ≈ 6 GB → still within 6 × t3.large but tight with inventory + notification → raise `max_size` to 10 or add Karpenter. DB: 200 RPS × ~3 queries × 10 ms = 6 connection-seconds/s → ~10 active connections; `db.t4g.medium` (2 vCPU) will be ~60–80% → go `db.r7g.large` for the week (Multi-AZ modify = failover ~1 min, do it at night). Kafka: trivial. Redis: product reads 10× → `cache.t4g.small` fine, check `EngineCPUUtilization`. NAT: image pulls at scale-up → ECR endpoints. Load test with k6/Gatling against a staging stack at 1.5× target, watch the breaker and the rate limiter (50/s/pod × 15 = 750/s, OK). Pre-scale 1 h before via KEDA cron; freeze deploys; on-call doubled.

### E12. Security review of ShopFlow's K8s manifests — what would you still fix?
Good: non-root, read-only FS, no capabilities, seccomp, no SA token automount, NetworkPolicies default-deny both ways, resource limits for memory, probes, PDB, secrets from ESO, TLS to RDS (`sslmode=require`), immutable images. Fix: (1) `/actuator/**` is `permitAll` — rely on NetworkPolicy + ALB path rules so it is not internet-reachable, and expose the management port separately (`management.server.port: 9090`) so the ALB cannot route to it at all; (2) Pod Security Admission labels on the namespace; (3) image signature verification; (4) `ipBlock 0.0.0.0/0:443` egress for Cognito JWKS is broad → Cilium FQDN policy or a VPC endpoint; (5) `endpoint_public_access` on EKS → CIDR allow-list; (6) KMS CMKs for RDS/EBS/secrets with rotation; (7) audit logging; (8) RBAC review for Argo CD (project `default` → a scoped `AppProject` with allowed repos/namespaces); (9) WAF; (10) CPU limits absent is a deliberate choice, document it in a policy exception.

---

## PART F — BEHAVIOURAL / SITUATIONAL FOR DEVOPS (6 questions)

Use **STAR** (Situation, Task, Action, Result) and add **L** (Learned). Keep to 2 minutes. Numbers make stories credible.

### F1. "Tell me about a production outage you handled" (ShopFlow-based, truthful framing: lab incident)
*S*: During a load test of ShopFlow on minikube, order-service started returning 503 and the `InventoryCircuitOpen` alert fired. *T*: find out whether inventory-service was really down or the breaker was mis-tuned, within minutes. *A*: checked `kubectl get pods` — inventory pods were `Running` but readiness failing; `kubectl logs` showed `FATAL: sorry, too many clients already` from Postgres; I had scaled pods to 10 with `DB_POOL_SIZE=10` → 100 connections per service, over the compose/minikube `max_connections=200` total with three services. Mitigation: scaled back to 3 pods (`kubectl scale` — in prod it would be a Git change), readiness recovered in 40 s, breaker closed after its 10-s open window. Fix: pool 5 per pod, documented the formula in `deployment.yaml` (`10 pods × 5 = 50 per service`), set `max_connections=300` in the RDS parameter group in Terraform, added the `hikaricp_connections_pending` panel. *R*: outage 6 min in the lab; the formula check is now part of my review checklist. *L*: scaling the stateless tier can kill the stateful tier; every autoscaler needs a ceiling derived from downstream limits. (If asked "was this real prod?" — say it was a lab incident; the handling is what they are evaluating.)

### F2. "Describe a time you disagreed with a senior engineer"
*S*: A senior wanted one `@Transactional` around `placeOrder`, including the HTTP call to inventory, "so it's atomic". *T*: I believed it would exhaust the connection pool under inventory latency. *A*: instead of arguing in the PR, I wrote a 20-line test with WireMock delaying responses by 2 s and 10 concurrent requests on a pool of 5 — it showed `connectionTimeout` errors within seconds, and I showed how the outbox + status machine gives the same guarantees (no lost events) without the pool risk. We agreed on the current design (`OrderService` with `TransactionTemplate` only around the DB writes). *R*: the design doc now has a "no network calls inside DB transactions" rule. *L*: disagree with data and a reproducible experiment, not opinions; make it easy for the senior to change their mind in public.

### F3. "Tell me about cutting cost without hurting reliability"
*S*: The AWS Terraform estimate came to ~$500–700/month for a three-service demo. *T*: cut by half for non-prod while keeping the prod design intact. *A*: made the expensive choices variables with safe prod defaults (`db_multi_az`, `single_nat_gateway`, instance sizes in `variables.tf`), documented the dev values in the README, planned Spot for the node group and VPC endpoints to replace NAT data charges, kept RF3/ISR2 on Kafka and Multi-AZ in prod because those protect data, not just availability. *R*: dev stack ~$250/month and `destroy` the same day for labs; prod unchanged. *L*: separate "costs that buy durability" (never cut) from "costs that buy convenience" (cut first); tag everything so the bill tells you where to look.

### F4. "A deploy you had to roll back"
Frame with GitOps: CI pinned a new SHA, Argo synced, `HighErrorRate` fired within 3 min because a Flyway migration added a `NOT NULL` column without a default and the old pods (still live during `maxUnavailable: 0` rollout) failed inserts. Action: `git revert` of the pin commit → Argo rolled back the image in ~60 s; the schema change stayed (forward-compatible once the default was added in a follow-up migration). Learned: expand/contract (E1), run migrations as a PreSync Job, and test the N-1 app against the N schema in CI.

### F5. "How do you handle on-call fatigue / noisy alerts?"
Alert on symptoms, not causes (ShopFlow rules: error rate, p99, outbox backlog, lag — the breaker alert is the one "cause" alert and it is justified as an early signal); every page must be actionable with a runbook link; `for:` durations to avoid flaps; weekly alert review: anything that paged > 3× without action gets deleted or demoted to a ticket; burn-rate alerts instead of static thresholds; a rotation of ≥ 6 people, comp time after night pages; measure pages per shift as a team health metric.

### F6. "What would you do in your first 30 days on our platform team?"
Week 1: read runbooks and postmortems, shadow on-call, map the architecture (draw it like §1 of 09-aws-production.md), get access, make one small PR (a doc fix or alert tweak). Week 2: own one toil item end-to-end (e.g. automate a manual DB-init step, like the one in ShopFlow's runbook). Week 3: review the SLOs and dashboards, propose one improvement with data. Week 4: take a secondary on-call shift, write a short "what confused me" doc for the next hire. Ask, don't assume; prefer reversible changes; earn trust before proposing re-architectures.

---

## PART G — CROSS-CUTTING HARD ONES (8 questions interviewers use to separate "read about it" from "did it")

### G1. Trace one `POST /api/v1/orders` through ShopFlow on AWS — every hop, every failure point
```
browser ─TLS─▶ Route 53 → ALB (ACM cert, WAF) ─HTTP─▶ pod IP (target-type ip) :8081
  Spring Security filter chain: BearerTokenAuthenticationFilter → JwtDecoder (JWKS from Cognito issuer, cached) → JwtRolesConverter (cognito:groups → ROLE_customer) → hasRole("customer")
  OrderController: @Valid body, @RateLimiter("orders") 50/s/pod → 429 if exceeded
  OrderService.placeOrder: findByIdempotencyKey (index hit) → save PENDING (tx 1, Hikari conn ~2 ms)
  InventoryClient.reserve: RestClient → inventory-service ClusterIP (kube-proxy DNAT) :8082, Retry(3, 200ms ×2) ∘ CircuitBreaker
     inventory: ReservationService.reserve @Transactional: findByOrderRef → decrementStock (row lock, WHERE qty >= n) → insert reservation → @CacheEvict products::sku in ElastiCache (TLS)
  back in order-service: order.confirm() → saveWithEvent (tx 2: orders UPDATE + outbox INSERT) → 201 + Location
  async: OutboxRelay (1 s) FOR UPDATE SKIP LOCKED → KafkaTemplate.send(orders.events, key=orderRef) acks=all over SASL_SSL/IAM :9098 → markPublished
  notification-service @KafkaListener: dedup processed_event → "EMAIL" log → commit offset
```
Failure points to name without being asked: JWKS fetch blocked by NetworkPolicy (needs `0.0.0.0/0:443`), ALB health checks denied unless the pod NetworkPolicy allows the VPC CIDR (`ipBlock 10.0.0.0/16` on 8081/8082 in the prod overlay), MSK IAM auth needs the pod to run as the IRSA-annotated ServiceAccount (`service-accounts.yaml`), rate limit per pod not global, inventory 409 → REJECTED (not retried), inventory timeout → FAILED + 503 + stock possibly reserved (settled by `OrderReconciler`: idempotent release + CANCELLED after 2m), DB down between tx 1 and tx 2 → PENDING orphan (still a gap), a customer guessing another customer's order id → 404 (ownership check on `customerId`), Kafka down → backlog alert, consumer crash → redelivery dedup. Latency budget: ALB 1 ms + auth 0.5 ms + DB 2×3 ms + inventory 15 ms (its DB + Redis) ≈ 25 ms p50; p99 dominated by GC and retries (≤ 1 s + 2 s read timeouts × 3 → set a total deadline).

### G2. Design CI/CD for 50 microservices owned by 8 teams
Monorepo vs polyrepo: polyrepo per team with a shared **reusable workflow** (`workflow_call`) / Jenkins shared library so pipelines stay identical (ShopFlow has both a GitHub workflow and a `Jenkinsfile` with the same stages: test → validate manifests → build + Trivy → push → pin overlay). Path filters so only changed services build (ShopFlow's `paths:` filter at repo level; per-service via `dorny/paths-filter`). Golden-path template repo with Dockerfile (one shared multi-stage Dockerfile parameterised by `SERVICE`, as ShopFlow does), Kustomize base, alert rules. Artifacts immutable by SHA, SBOM + signature attached. Deployment: Argo CD **ApplicationSet** generating one Application per service × environment from a directory layout; promotion = PR that bumps the tag in `overlays/prod` (bot-created, human-approved for prod, auto for dev). Policy as code (Kyverno/OPA conftest in CI: no `:latest`, requests set, non-root). Build cache: GHA cache or remote BuildKit; Maven dependency cache. Metrics: DORA four keys from the Git + Argo data. Guardrails: environments with required reviewers, OIDC to clouds (`ci-oidc.tf`), action SHA pinning. Scale pain points: flaky integration tests (quarantine lane), shared staging contention (ephemeral namespaces per PR with `kustomize edit set namespace`), DB migrations ownership (each service owns its Flyway dir, PreSync Job).

### G3. Postgres deep dive: why can `UPDATE ... WHERE quantity >= :qty` under MVCC still never oversell?
MVCC gives readers snapshots, but **writers lock the row**: the first `UPDATE` takes a row-level lock; the second transaction's `UPDATE` on the same row blocks until the first commits, then Postgres **re-evaluates the WHERE clause on the new row version** (in READ COMMITTED) — so the second sees `quantity = 2` instead of 3 and either proceeds or matches zero rows. That re-check is why `ProductRepository.decrementStock` returning `0` is a reliable "insufficient" signal with 20 concurrent threads (the concurrency test in `inventory-service` proves it). In REPEATABLE READ/SERIALIZABLE the second would instead fail with a serialization error and need a retry. Follow-ups: hot-row contention on one SKU → lock queue → latency (flash sale: shard the counter or move admission to Redis, see A1); `SELECT ... FOR UPDATE` vs atomic update (atomic is one round-trip); `SKIP LOCKED` for queues (`OutboxRepository`); deadlocks when two transactions update two SKUs in opposite order → always sort SKUs in multi-item orders.

### G4. Spring Security resource-server internals: what exactly is validated in a Cognito JWT and what is not?
`NimbusJwtDecoder` built from `issuer-uri`: fetches `/.well-known/openid-configuration` → `jwks_uri`; caches keys, refreshes on unknown `kid`. Validators by default: signature (RS256 against JWKS), `exp`/`nbf` with 60 s skew, `iss` equals the configured issuer. **Not** validated by default: `aud` (Cognito access tokens have no `aud`, they have `client_id`), `token_use`, scopes, revocation (JWTs are stateless — a signed-out user's token works until `exp`, hence 15-min lifetime in `cognito.tf`), `azp`. Hardening: add `JwtClaimValidator<String>("client_id", id::equals)` and `token_use == access`; map `scope` claim to `SCOPE_` authorities if using scopes. `JwtRolesConverter` handles both Keycloak `realm_access.roles` and Cognito `cognito:groups` so the same image runs in both environments. Probes `/actuator/**` are `permitAll` — protected by network, not identity (state the trade-off). Authorization beyond the token: the URL rules are role-level only; object-level authorization is done in `OrderService` with `Caller.mayAccess` (JWT `sub` vs `Order.customerId`, 404 for foreign ids) — a JWT validator cannot do that part. CSRF disabled because stateless bearer auth has no cookie to forge. Method security `@EnableMethodSecurity` for ownership checks (`@PreAuthorize("#order.customerId == authentication.name")` — not implemented in ShopFlow, a known gap: a customer can read any order by id).

### G5. Kubernetes DNS gotchas that cause real outages (`ndots`, CoreDNS load, conntrack)
Default pod `resolv.conf`: `ndots:5` + search domains (`shopflow.svc.cluster.local`, `svc.cluster.local`, `cluster.local`, VPC domain). A lookup for `shopflow.xxxx.ap-south-1.rds.amazonaws.com` (4 dots < 5) tries **4 search suffixes first** → 5 queries (×2 for A+AAAA) before the right one → CoreDNS load and latency; fix with a trailing dot (`...amazonaws.com.`), `dnsConfig.options ndots: 2`, or NodeLocal DNSCache. Conntrack races on UDP DNS (`insert_failed`) caused 5-s timeouts in older kernels → NodeLocal DNSCache again, or `single-request-reopen`. CoreDNS autoscaling (`cluster-proportional-autoscaler`), its own PDB, and the `default-deny-egress-allow-dns` NetworkPolicy in ShopFlow allowing 53 to `kube-dns` — a wrong label there breaks *every* pod. JVM caches DNS: `networkaddress.cache.ttl` (default 30 s without a security manager) — matters for RDS/Redis failover. ExternalName Services for managed endpoints (an alternative to ShopFlow's ConfigMap approach: `Service postgres → type ExternalName → rds host`, keeps the base manifests' `postgres:5432` URL).

### G6. Kafka producer semantics under retries: ordering, duplicates, `max.in.flight`
Without idempotence, `retries > 0` + `max.in.flight.requests.per.connection > 1` can **reorder** batches (batch 2 succeeds, batch 1 retried later). With `enable.idempotence=true` (ShopFlow) the producer gets a PID + per-partition sequence numbers; the broker rejects duplicates and preserves order with up to 5 in-flight requests; `acks` is forced to `all`, `retries` to MAX. Still not covered: application-level retries that create a *new* record (e.g. the relay restarting after a crash between `send().get()` and `markPublished()` → the same event published twice with a new producer session) — hence consumer-side dedup by `eventId`. `delivery.timeout.ms` (2 min) bounds the total; `linger.ms`/`batch.size` trade latency for throughput (relay sends one record at a time with `.get()` — throughput-limited to ~100s/s per pod; batching with callbacks would be the optimisation). Transactions (`transactional.id`) give atomic multi-partition writes + offset commits for consume-transform-produce, not for DB side effects. Compaction vs delete retention; key `null` → sticky partitioner.

### G7. You inherit a cluster with no docs. How do you build a picture in a day?
Inventory: `kubectl get ns`, `kubectl get all -A`, CRDs (`kubectl get crd` — tells you the operators: Argo, ESO, cert-manager, Prometheus), `kubectl get ingress -A`, `kubectl get netpol -A`, `kubectl get pdb -A`, `kubectl top nodes/pods`, `kubectl get events -A --sort-by=.lastTimestamp` (what is unhappy right now). Identity: `aws eks list-access-entries`, SA annotations for IRSA, `kubectl auth can-i --list`. Dependencies: Services with no endpoints, ConfigMaps with hostnames (ShopFlow pattern: `shopflow-endpoints`), ExternalSecrets status. GitOps: Argo apps and their repos = the real source. Infra: `terraform state list` if there is state; else `aws resourcegroupstaggingapi get-resources` by tag; Cost Explorer by service. Draw the diagram (public/private/database layers), list the top 5 risks (single replica + PDB `minAvailable 1`, `:latest` tags, secrets in Git, no backups tested, admin-for-all RBAC), and the top 3 quick wins. Write it down — the doc you wish you had been given.

### G8. Argue against your own architecture: when is ShopFlow-on-EKS the wrong choice?
For three small services and one team, EKS + MSK + RDS + ElastiCache is **over-built**: ~$500/month and four managed systems to understand. Cheaper, simpler shapes that would serve the same requirements: ECS Fargate + RDS + SQS/SNS (no Kafka, at-least-once with FIFO queues and dedup IDs; the outbox pattern still applies) ≈ $150/month; or a modular monolith on two EC2 instances behind an ALB with Postgres, using Spring Modulith events — the idempotency, saga and observability work stays, the network boundaries disappear. Kafka earns its place only with multiple consumers, replay needs or > ~1k events/s; Kubernetes earns its place with many services/teams or when portability matters. The honest statement: "I built it this way to learn and demonstrate the production patterns; for a real business of this size I would start with the simpler shape and keep the same code-level guarantees." Senior interviewers reward this answer more than defending the stack.

---

## Questions to ask the interviewer (pick 3)

1. How do deploys reach production today — push from CI or GitOps pull? How long from merge to prod?
2. What does your on-call rotation look like, and what was the last Sev-1 about?
3. Do you have SLOs and an error-budget policy, or are you building that?
4. How much of the infra is in Terraform/IaC, and who is allowed to click in the console?
5. EKS, ECS or something else — and what drove that choice?
6. How are secrets managed and rotated? Any IAM users with access keys left?
7. What is the biggest source of toil for the team right now?
8. How do you test infra changes before prod (ephemeral environments, staging parity)?
9. How do developers get a production-like environment locally (something like ShopFlow's docker-compose)?
10. What would success look like for this role after six months?

> Tip: Sawaal pooch kar chup ho jao; interviewer ke jawab pe ek follow-up pooch lo. Yeh "curious engineer" signal deta hai, "interview khatam karo" nahi.

---

## 48-hour revision plan

**Day 1 (concepts + project, ~8 h)**
| Time | Do |
|---|---|
| 0:00–0:45 | `01-project-walkthrough.md` pitch out loud 3×; draw the ShopFlow diagram from memory incl. AWS layer (`09-aws-production.md` §1) |
| 0:45–2:00 | Part A: pick A1, A4, A5; speak each 5-step answer in 4 min with a timer; write the capacity numbers on paper |
| 2:00–3:00 | Part B: B5, B7, B9 (the three most asked for Java-on-K8s); re-read `OrderService.java`, `OutboxRelay.java`, `OrderEventListener.java` |
| 3:00–3:15 | break |
| 3:15–4:30 | Part C: C1 and C8 with the diagrams; `kubectl explain deployment.spec.strategy` on minikube; redo the LABS.md graceful-shutdown lab if time |
| 4:30–5:30 | Part D: D1, D2, D8 — practise saying "at-least-once + idempotent" naturally; list the honest gap (PENDING sweeper) and what was closed (FAILED reconciliation, ownership/IDOR, outbox retention) |
| 5:30–6:30 | `09-aws-production.md` §§2–5, 10 (VPC, EKS, RDS, IAM/OIDC trust policy) + Q&A Q1–Q15 |
| 6:30–7:30 | Part E: E1, E3, E4 — write your own 5-minute Sev-1 checklist on one page |
| 7:30–8:00 | Part F: write your 3 STAR stories in bullet form (max 6 bullets each) |

**Day 2 (recall + weak spots + rest, ~6 h)**
| Time | Do |
|---|---|
| 0:00–0:30 | Rapid-fire table in `README.md`; cover answers, say them |
| 0:30–1:30 | Mock interview: a friend/ChatGPT asks 10 random questions from this file; record yourself; note anything > 4 min or hesitant |
| 1:30–2:30 | Fix the 3 weakest answers by re-reading only those sections |
| 2:30–3:15 | `09-aws-production.md` §§6–9, 13–16 (ElastiCache, MSK, Cognito, cost, DR, runbook) + scenarios S1–S8 |
| 3:15–4:00 | Terraform walk: open each `.tf`, say what it creates and one "why" per file; practise the honesty line ("fmt + validate in CI, not applied") |
| 4:00–4:45 | Part C remaining (C3, C4, C7) + Part D remaining (D4, D5, D7) |
| 4:45–5:15 | Questions to ask + your closing statement (30 s: what you bring, what you want to learn) |
| 5:15–6:00 | Stop. Sleep ≥ 7 h. Do not learn anything new on interview morning; re-read only the pitch and your STAR bullets |

> Tip: Last 48 hours mein naya topic mat chhedo. Jo aata hai usko confidently bolna 10 half-known topics se zyada marks deta hai. Aur haan — "I don't know, but here's how I'd find out" ek valid, strong answer hai.
