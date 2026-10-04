# Interview Notes: Java Backend + DevOps

Every file follows the same pattern: **concept → how ShopFlow uses it (with file paths) → interview Q&A → scenarios → common mistakes**. Answers are in English because interviews are in English. The `> Tip:` lines are short Hinglish hints.

| # | File | Read it when |
|---|---|---|
| 01 | [Project walkthrough](01-project-walkthrough.md) | **Always first.** "Tell me about your project" decides how the rest of the interview goes |
| 02 | [Java + Spring Boot](02-java-spring-boot.md) | Backend / Java developer rounds |
| 03 | [Microservices](03-microservices.md) | System design and microservices rounds |
| 04 | [Docker](04-docker.md) | DevOps rounds, and "how do you deploy?" |
| 05 | [Kubernetes](05-kubernetes.md) | DevOps / platform rounds (the most-asked topic) |
| 06 | [CI/CD + GitOps](06-cicd-gitops.md) | "Explain your pipeline" |
| 07 | [Observability + SRE](07-observability-sre.md) | On-call, production-debugging and SRE rounds |
| 08 | [Linux, networking, cloud, IaC](08-linux-networking-cloud-iac.md) | DevOps fundamentals, AWS, Terraform |
| 09 | [AWS production](09-aws-production.md) | "How would this run on AWS?" — EKS, RDS, MSK, ElastiCache, Cognito, IRSA, OIDC (`infra/terraform/aws`) |
| 10 | [Kafka & event-driven](10-kafka-event-driven.md) | Kafka rounds: outbox, idempotent consumer, DLT, lag, ordering, exactly-once |
| 11 | [Security, caching, performance, tracing](11-security-caching-performance.md) | OAuth2/JWT (incl. audience validation), ArchUnit guardrail, Spring Cache + Redis, rate limiting, virtual threads, k6 (`loadtest/flash-sale.js`), tracing with Tempo/Loki |
| 12 | [Hard interview questions](12-hard-interview-questions.md) | Senior-level follow-ups and curveballs across all topics |

## How to prepare (not just read)

1. **Run the labs first** ([`../LABS.md`](../LABS.md)). Answers stick only after you've seen the thing break yourself.
2. Read a file, close it, and **answer the Q&A out loud** in 2–4 sentences. If you can't, re-read only that part.
3. For every answer, add *"in my project, ..."* using ShopFlow. Interviewers trust experience more than definitions.
4. One week before the interview, revise only the rapid-fire sheet below plus the 01 walkthrough.

> Tip: Rattna nahi hai. Har answer ka "why" samjho. Follow-up question hamesha "why" pe hi aata hai.

---

## Rapid-fire revision (1-line answers)

### Project
| Q | A |
|---|---|
| How do you prevent overselling? | One atomic SQL statement, `UPDATE ... SET quantity = quantity - n WHERE sku = ? AND quantity >= n`. The row lock serialises buyers and the WHERE re-checks the stock. A 20-thread test proves it. |
| How do you avoid duplicate orders? | An `Idempotency-Key` header with a unique column on orders, and `orderRef` as a unique key on reservations, so retries are safe. |
| Why no `@Transactional` around the inventory call? | Holding a DB connection while waiting on the network exhausts the pool. Instead each step is saved separately and the order status records progress. |
| What if inventory is down? | Timeout → retry with backoff (transient errors only) → circuit breaker opens → fast 503. The order is marked `FAILED` and readiness stays UP. |
| What happens to a `FAILED` order? | `OrderReconciler` runs every 60s: FAILED **or PENDING** for more than 2 minutes → idempotent `release(orderRef)` → `CANCELLED` + `OrderCancelled` event. If inventory is still down the batch stops and retries next minute. No order is left in limbo. |
| How does an order become `PAID`? | `OrderConfirmed` on `orders.events` → payment-service (group `payment-service`) charges via the `PaymentGateway` interface and writes the `payment` row + a `PaymentCaptured`/`PaymentFailed` outbox row in one transaction → `payments.events` → order-service `PaymentEventListener` (group `order-service`, `processed_event` dedupe) → `PAID`, or on failure `release` + `CANCELLED("payment failed: …")`. Only CONFIRMED orders move, so duplicates are no-ops. |
| Orchestration or choreography? | Both: reserving stock is orchestrated by `OrderService` over REST (the customer needs the answer now); payment and notification are choreographed over Kafka (payment-service reacts to `OrderConfirmed`, order-service reacts to `PaymentCaptured`/`PaymentFailed`). Visibility comes from tracing, safety from status guards + idempotent consumers. |
| How is payment-service idempotent? | The `payment` table's primary key **is** the `OrderConfirmed` `eventId`: a redelivery hits `existsById` and is skipped, a race hits the PK. Charge + row + outgoing event are one local transaction (`redeliveredEventChargesOnlyOnce`). |
| Why doesn't readiness include inventory? | Otherwise one service's outage would pull the healthy order pods out of the load balancer too (a cascading failure). |
| How does an order reach notification-service? | `saveWithEvent` writes the order and an `outbox_event` row in one `TransactionTemplate`; `OutboxRelay` polls every 1s with `FOR UPDATE SKIP LOCKED`, publishes to `orders.events` keyed by `orderRef` with `acks=all`; the consumer dedupes on `eventId` in `processed_event`. Published outbox rows are deleted after 7 days by a daily cleanup; unpublished ones never. |
| How is the API secured? | order-service is an OAuth2 resource server: stateless JWT (RS256 via the issuer's JWKS), roles from Keycloak `realm_access.roles` or Cognito `cognito:groups` → `ROLE_*`; GET needs `customer`/`support`, writes need `customer`; 401 vs 403 tested with `jwt()`. |
| Can a customer read someone else's order? (IDOR) | No. Orders store the JWT `sub` (`customerId`); `OrderService` checks `Caller.mayAccess` (owner or `support`) on get/cancel and scopes the list with `findByCustomerId`. Another customer's `Idempotency-Key` → 422. |
| Why 404 and not 403 for a foreign order? | 403 confirms the id exists and makes sequential ids enumerable; the same 404 as for a missing id reveals nothing. |
| What is cached and when is it evicted? | `GET /products/{sku}` caches the `ProductResponse` DTO (`@Cacheable("products")`); `reserve` evicts that SKU, `release` evicts all; Redis with 60s TTL in compose/K8s. Stock decisions never read the cache. |
| What if Redis is down? | Nothing breaks: `CacheConfig` implements `CachingConfigurer` with `LoggingCacheErrorHandler`, so cache errors are logged and reads fall through to Postgres (fail-open); readiness excludes Redis. |
| What happens on a traffic burst? | `@RateLimiter("orders")`: 50 order creations per second per pod, no queueing → 429 ProblemDetail; reads are unlimited. Per pod, not global — a gateway/Redis limiter is the next step. |

### Java / Spring
| Q | A |
|---|---|
| HashMap internals? | An array of buckets indexed by `hash(key)`. Collisions are chained, and a bucket becomes a tree after 8 entries (when capacity ≥ 64). It resizes at 0.75 load factor. |
| `@Transactional` not working? | It's proxy-based: self-invocation, private methods, or a checked exception (no rollback by default) are the usual causes. |
| N+1 problem? | 1 query for the parents + N lazy queries for the children. Fix it with a fetch join / `@EntityGraph` / batch fetching. |
| Optimistic vs pessimistic locking? | Optimistic: a `@Version` check at update time, which fails on conflict (good when conflicts are rare). Pessimistic: `SELECT ... FOR UPDATE`, which blocks others. |
| Bean scopes? | singleton (default), prototype, request, session, application. |
| What is auto-configuration? | `@Conditional...` configuration classes that run when a class or property is present. Starters bring those dependencies in. |
| Virtual threads (Java 21)? | Cheap JVM-managed threads, great for blocking IO. Spring Boot enables them with `spring.threads.virtual.enabled=true` (on in all four ShopFlow services). Watch for pinning in `synchronized`; the DB pool becomes the real limit. |
| `@Transactional` vs `TransactionTemplate`? | Same transaction manager; the annotation needs a proxy call (fails silently on self-invocation), the template works anywhere. ShopFlow's private `saveWithEvent` uses the template for exactly that reason. |
| `@Cacheable` pitfalls? | Proxy-based (self-invocation ignored), caches `null` by default, cache DTOs not entities, Redis needs a serializer (JSON) — a record without `Serializable` breaks JDK serialization. |

### Microservices
| Q | A |
|---|---|
| Saga? | A sequence of local transactions with compensations instead of a distributed transaction. ShopFlow: reserve (REST) → `OrderConfirmed` → payment → `PaymentCaptured`/`PaymentFailed` → PAID or release + CANCELLED; client cancel → release is the other compensation. |
| Transactional outbox? | Write the business row and an event row in the same DB transaction, and have a relay publish the event to Kafka. This fixes the dual-write problem. ShopFlow: `outbox_event` + `OutboxRelay` (polling publisher, at-least-once). |
| Circuit breaker states? | CLOSED → (failure rate ≥ threshold) OPEN → (after a wait) HALF_OPEN → test calls → CLOSED or back to OPEN. |
| Retry best practice? | Retry only transient, idempotent operations, with exponential backoff + jitter, a max-attempts cap, and a timeout on every call. |
| Kafka ordering? | Guaranteed only within a partition, so use a key (e.g. `orderRef`) to keep related events together; changing the partition count breaks the mapping. |
| Exactly-once in Kafka? | Only Kafka→Kafka with idempotent producer + transactions. With a DB/email in the loop: at-least-once + idempotent consumer (ShopFlow: `processed_event` keyed by `eventId` in notification- and order-service; `payment.event_id` PK in payment-service). |
| Poison pill? | A record that always fails. `DefaultErrorHandler` retries with `ExponentialBackOff` (0.5s ×2, ≤10s) then `DeadLetterPublishingRecoverer` moves it to `orders.events.DLT`; the partition moves on. |
| `acks=all` + `min.insync.replicas`? | The write is acknowledged only when every in-sync replica has it; with RF 3 / min ISR 2 one broker can die without losing acknowledged records (MSK config). |
| Consumer lag growing? | Consumers ≤ partitions (3 local / 6 MSK), then faster processing, then more partitions; alert `KafkaConsumerLagHigh`. Check for a poison-pill retry loop first. Lag on group `payment-service` means orders sit CONFIRMED with stock reserved. |
| Rate limiter vs bulkhead vs breaker? | Requests per time (ShopFlow: 50/s/pod → 429) / concurrent calls to a dependency / stop calling a failing dependency. |
| JWT validation steps? | Fetch JWKS from the issuer (`issuer-uri` → discovery, lazily via `SupplierJwtDecoder`), pick the key by `kid`, verify RS256 signature, check `exp`, `iss` and `aud` (`JwtDecoderConfig`: `aud`/`azp`/`client_id` must equal `JWT_AUDIENCE` when set — Keycloak vs Cognito shapes), map claims to authorities. |
| What does ArchUnit guard? | 7 build-failing rules in order-service: controllers never depend on `*Repository` or `@Entity` classes, `*Service` never depends on Spring Web/servlet (must stay callable from Kafka listeners), `@RestController`s are named `*Controller`, no field injection, no `System.out`, no `java.util.logging`. |
| Why RS256 not HS256? | Asymmetric: only the IdP holds the private key; services need just public keys, so no shared secret to leak or rotate across services. |
| Cache-aside? | App reads cache → miss → DB → put; on write, update the DB and **evict**. ShopFlow evicts rather than updates because the stock change is an atomic SQL update whose result the app never loads. |
| Traces ↔ logs? | Boot puts `trace.id`/`span.id` into ECS JSON logs; Loki's derived field links to Tempo, Tempo's traces-to-logs links back; `traceparent` travels in HTTP and Kafka headers. |

### Docker
| Q | A |
|---|---|
| CMD vs ENTRYPOINT? | ENTRYPOINT is the executable and CMD is its default arguments, which are easy to override. Use the exec (JSON) form so the app is PID 1 and receives SIGTERM. |
| Why multi-stage? | The build tools stay in the build stage, so the final image has only the JRE + app: smaller, with less attack surface. |
| Why layered jars? | Dependencies change rarely, so their layer stays cached, and a code change ships only a small layer. |
| Why not `:latest`? | It isn't immutable or traceable, so you can't roll back reliably. Tag with the git SHA. |

### Kubernetes
| Q | A |
|---|---|
| Liveness vs readiness vs startup? | Liveness failure = restart. Readiness failure = no traffic. Startup = gives a slow app time before liveness starts checking. |
| CrashLoopBackOff, how to debug? | `kubectl logs <pod> --previous`, `kubectl describe pod` (exit code, events), then check config/secrets and resources. |
| Exit code 137? | SIGKILL, usually OOMKilled (memory over the limit). Check `describe pod` → Last State. |
| QoS classes? | Guaranteed (requests = limits for every resource), Burstable (some requests), BestEffort (none, evicted first). |
| Zero-downtime deploy? | `maxUnavailable: 0` + readiness probe + `preStop` sleep + graceful shutdown. |
| ClusterIP vs NodePort vs LoadBalancer? | Internal only / a port on every node / a cloud load balancer in front (each type builds on the previous one). |
| StatefulSet vs Deployment? | StatefulSet gives stable pod names, ordered start-up, and a PVC per pod. Use it for databases. |
| Secret is encrypted? | No, only base64. Use encryption at rest + RBAC + an external secret manager. |

### CI/CD + observability
| Q | A |
|---|---|
| Your pipeline? | test (46) → validate manifests + Terraform + `k6 inspect` → build 4 images → Trivy scan → CycloneDX SBOM → push (main only) → keyless cosign sign → pin SHA into the prod overlay → Argo CD syncs. Same stages in `shopflow/Jenkinsfile`. |
| SBOM and signing? | `anchore/sbom-action` (Syft) emits `sbom-<service>.cdx.json` per image; `cosign sign --yes <image>@<digest>` signs the pushed digest with the job's OIDC identity (`id-token: write`) — no key to leak, verify by identity (`repo`/`ref`), Rekor log. Admission verification (Kyverno) is the next step. |
| k6 threshold that matters most? | `orders_confirmed: ['count==3']` in `shopflow/loadtest/flash-sale.js`: 300 buyers, 3 seeded PS5s, exactly 3 CONFIRMED. Also order p95 < 800ms, browse p99 < 300ms, `http_req_failed` < 1% (429/409 are not failures). |
| Kustomize vs Helm? | Kustomize (`k8s/`): copy + patch, no logic, what Argo CD syncs — but `base/<service>` is copied per service. Helm (`helm/shopflow-service`): one generic chart, a ~15-line values file per service, `helm rollback` per release, standard for vendor charts. Rule of thumb (`helm/README.md`): the fourth copy of `base/<service>` is when you want a chart. |
| New alerts for the async path? | `OutboxBacklogGrowing` (`outbox_unpublished > 100` for 5m, page: Kafka down or relay stuck while HTTP still returns 201), `KafkaConsumerLagHigh` (lag > 1000 for 10m, ticket) and `PaymentFailureRateHigh` (`payments_total{status="FAILED"}` ratio > 0.5 for 10m, page: gateway or pricing broken, every failure cancels an order). |
| Blue-green vs canary? | Blue-green switches all traffic at once and rolls back instantly. Canary shifts traffic gradually, which limits the blast radius. |
| Error-rate PromQL? | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| p99 latency PromQL? | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))` |
| SLI / SLO / SLA? | Measurement / internal target / contractual promise with penalties. Error budget = 1 − SLO. |
