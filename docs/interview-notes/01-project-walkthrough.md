# 01 — ShopFlow Project Walkthrough (How to Explain It in an Interview)

> Source of truth: `shopflow/` in this repo. Every class name, file path and config key below exists in the code.
> If an interviewer asks about something not listed here, say honestly "not in this project — this is how I would add it".

---

## 0. Quick facts (memorise this table)

| Item | Value |
|---|---|
| Build | Maven multi-module: `shopflow/pom.xml` (parent) → `inventory-service`, `order-service`, `notification-service` |
| Stack | Spring Boot **3.5.x**, Java **21** (virtual threads on in all three), PostgreSQL, Flyway, Spring Data JPA (Hibernate), Spring Kafka |
| Resilience | Resilience4j **2.4** (`resilience4j-spring-boot3` + `spring-boot-starter-aop`) — only in order-service: retry, circuit breaker, **rate limiter** (`@RateLimiter("orders")`, 50 rps/pod → 429) |
| Messaging | **Transactional outbox** (`outbox_event` table, `OutboxRelay` `@Scheduled` + `FOR UPDATE SKIP LOCKED`) → Kafka topic `orders.events` (key `orderRef`, `acks=all`, idempotent producer) → **notification-service** idempotent consumer (`processed_event`), DLT `orders.events.DLT` |
| Security | order-service is an **OAuth2 resource server** (stateless JWT; Keycloak locally, Cognito in prod; `JwtRolesConverter` maps `realm_access.roles` / `cognito:groups` → `ROLE_*`); inventory/notification have no app auth (NetworkPolicy only) |
| Caching | inventory-service `@EnableCaching`; `@Cacheable("products")` on `GET /products/{sku}` (DTO), `@CacheEvict` in `reserve`/`release`; `CACHE_TYPE=simple` default, `redis` in compose/K8s, TTL 60s |
| HTTP client | Spring `RestClient` with `SimpleClientHttpRequestFactory` (connect 1s, read 2s) |
| API docs | springdoc-openapi → `/swagger-ui.html` |
| Observability | Actuator (`health`, `info`, `prometheus` exposed), Micrometer Prometheus registry, custom counters; **tracing** via `micrometer-tracing-bridge-otel` + OTLP exporter → Tempo; ECS JSON logs → Loki (Alloy); Grafana links traces↔logs |
| Error format | RFC 7807 `ProblemDetail` (`application/problem+json`) via `@RestControllerAdvice` (incl. 429 for rate limit) |
| Tests | `@SpringBootTest` + `MockMvc`, H2 in `MODE=PostgreSQL`, WireMock standalone for inventory, `@EmbeddedKafka`, `spring-security-test` `jwt()`; one `@WebMvcTest` slice. Counts: inventory **10**, order **12 + 2**, notification **2** = 26 |
| Ports | order-service **8081**, inventory-service **8082**, notification-service **8083**, Keycloak **8180** |
| Packaging | `<finalName>app</finalName>` → `target/app.jar`; `build-info` goal → version at `/actuator/info` |
| Container | one multi-stage, layered, non-root `shopflow/Dockerfile` (`--build-arg SERVICE=...`), `MaxRAMPercentage=75` |
| Local stack | `shopflow/docker-compose.yml`: postgres + Kafka (KRaft) + Redis + Keycloak + three services + Prometheus + Grafana + Tempo + Loki + Alloy |
| Kubernetes | `shopflow/k8s/` Kustomize base + `overlays/dev` / `overlays/prod`: Deployments, Services, HPA, PDB, Ingress, NetworkPolicies, Postgres/Kafka StatefulSets, Redis + Keycloak Deployments, `shopflow-endpoints` ConfigMap; prod overlay **deletes** Postgres/Kafka/Redis/Keycloak in favour of RDS/MSK/ElastiCache/Cognito (`infra/terraform/aws`) |
| CI/CD | `.github/workflows/shopflow.yml`: `./mvnw -B verify` → kustomize + kubeconform + promtool → Terraform fmt/validate → image build (3 services) → Trivy scan → push to GHCR on `main` → `deploy-manifests` pins the SHA into `overlays/prod` → Argo CD (`shopflow/argocd/`) syncs. `shopflow/Jenkinsfile` = same pipeline for Jenkins |
| Alerts | `shopflow/monitoring/alert-rules.yml`: HighErrorRate, HighP99Latency, InventoryCircuitOpen, ServiceDown, **OutboxBacklogGrowing**, **KafkaConsumerLagHigh** |

Key files:

```
shopflow/
├── pom.xml                                   # parent: shared deps, versions (java 21, r4j, springdoc, wiremock)
├── inventory-service/
│   └── src/main/java/com/shopflow/inventory/
│       ├── product/ProductRepository.java    # atomic decrementStock / incrementStock (@Modifying JPQL)
│       ├── product/ProductController.java    # GET /api/v1/products, /api/v1/products/{sku}
│       ├── reservation/ReservationService.java  # idempotent reserve/release, rejected counter, @CacheEvict
│       ├── reservation/ReservationController.java # POST /api/v1/reservations, DELETE /{orderRef}
│       ├── reservation/Reservation.java      # unique order_ref, status RESERVED/RELEASED
│       └── common/GlobalExceptionHandler.java  # 404 / 409 ProblemDetail
│   └── src/main/resources/db/migration/V1__create_tables.sql, V2__seed_products.sql
├── order-service/
│   └── src/main/java/com/shopflow/order/
│       ├── order/OrderController.java        # POST/GET/DELETE /api/v1/orders, Idempotency-Key header, @RateLimiter
│       ├── order/OrderService.java           # placeOrder / cancel — NO @Transactional; saveWithEvent (TransactionTemplate)
│       ├── order/Order.java                  # @Version, orderRef (UUID), idempotencyKey
│       ├── order/OrderStatus.java            # PENDING, CONFIRMED, REJECTED, FAILED, CANCELLED
│       ├── inventory/InventoryClient.java    # RestClient + @Retry + @CircuitBreaker
│       ├── inventory/InventoryProperties.java # @ConfigurationProperties(prefix="inventory") record
│       ├── inventory/InventoryRejectedException.java # business "no" — never retried
│       ├── outbox/OutboxEvent.java           # outbox_event entity (aggregate_id = Kafka key)
│       ├── outbox/OutboxRepository.java      # findUnpublished: PESSIMISTIC_WRITE + lock timeout -2 = SKIP LOCKED
│       ├── outbox/OutboxRelay.java           # @Scheduled poller → KafkaTemplate, outbox.unpublished gauge
│       ├── outbox/OrderEvent.java            # event contract, TOPIC = "orders.events"
│       ├── security/SecurityConfig.java      # resource server, stateless, role rules
│       ├── security/JwtRolesConverter.java   # realm_access.roles | cognito:groups → ROLE_*
│       └── common/GlobalExceptionHandler.java # 404 / 409 / 429 / 503 ProblemDetail
│   └── src/main/resources/db/migration/V1__create_orders.sql, V2__outbox.sql
└── notification-service/
    └── src/main/java/com/shopflow/notification/
        ├── OrderEventListener.java           # @KafkaListener + @Transactional, idempotent via processed_event
        ├── ProcessedEvent.java, ProcessedEventRepository.java
        ├── KafkaErrorConfig.java             # DefaultErrorHandler + ExponentialBackOff → orders.events.DLT
        └── OrderEvent.java                   # copy of the contract, @JsonIgnoreProperties(ignoreUnknown)
    └── src/main/resources/db/migration/V1__processed_event.sql
├── Dockerfile, .dockerignore, docker-compose.yml, Jenkinsfile, argocd/
├── k8s/base/{order-service,inventory-service,notification-service,postgres,kafka,redis,keycloak}/,
│   endpoints-configmap.yaml, ingress.yaml, network-policies.yaml
├── k8s/overlays/{dev,prod}/kustomization.yaml (+ prod/external-secret.yaml)
└── monitoring/prometheus.yml, alert-rules.yml, tempo.yml, alloy.river, grafana/provisioning/
(.github/workflows/shopflow.yml and infra/terraform/aws/ at repo root)
```

---

## 1. The 60-second pitch

Say this almost word for word, then stop and let them ask questions.

> "ShopFlow is a small but production-style e-commerce backend with three Spring Boot 3.5 / Java 21 microservices, each with its own PostgreSQL database managed by Flyway.
>
> **inventory-service** owns product stock. It exposes a reservation API. Reservations are **idempotent by `orderRef`**, and the stock decrement is a **single atomic SQL `UPDATE ... WHERE quantity >= :qty`**, so we can never oversell — I have a concurrency test where 20 threads fight over 3 PS5s and exactly 3 win. Product reads are cached with Spring Cache (Redis in compose/K8s) and evicted on every reservation.
>
> **order-service** accepts orders behind **OAuth2 / JWT** (Keycloak locally, Cognito on AWS) with a per-pod **rate limiter**. It calls inventory over HTTP with `RestClient`, with **connect/read timeouts, Resilience4j retry with exponential backoff, and a circuit breaker**. Clients can send an **`Idempotency-Key`** header so a retried POST never creates a duplicate order. There is no distributed transaction: each order moves through states — PENDING, CONFIRMED, REJECTED, FAILED, CANCELLED — and cancel acts as the **saga compensation** step that releases the stock. Every final state change writes an event into a **transactional outbox** in the same DB transaction; a relay publishes it to **Kafka** (`orders.events`, keyed by `orderRef`).
>
> **notification-service** consumes those events and sends the customer notification. It is an **idempotent consumer** (a `processed_event` table in the same transaction as the side effect), and poison messages are retried with backoff and moved to a **dead-letter topic**.
>
> Operationally all services have RFC 7807 error responses, Bean Validation, Kubernetes liveness/readiness probes, Prometheus metrics including business counters, **distributed tracing** (Micrometer → OTLP → Tempo, with trace ids in JSON logs shipped to Loki), graceful shutdown and OpenAPI docs. Tests run the full Spring context with MockMvc, H2 in Postgres mode, an embedded Kafka broker, fake JWTs and WireMock to simulate inventory failures like 503s and outages.
>
> It ships as a layered non-root Docker image, runs locally with Docker Compose plus Kafka, Redis, Keycloak and the Grafana stack, and deploys to Kubernetes with Kustomize overlays — HPA, PodDisruptionBudget, NetworkPolicies, Ingress — through a GitHub Actions pipeline that tests, validates manifests and Terraform, scans the images with Trivy, pushes to GHCR and pins the SHA for Argo CD. The prod overlay swaps the in-cluster Postgres/Kafka/Redis/Keycloak for RDS, MSK, ElastiCache and Cognito provisioned by Terraform."

> Tip: Pitch me "problem → design → proof (test)" pattern use karo. Har claim ke saath ek test ya config key bolo — interviewer ko lagta hai tumne khud likha hai.

---

## 2. Architecture

```
  Keycloak :8180 (JWT issuer, JWKS)
        ▲ validate token            ┌──────────────────────────────────────────────┐
  Client / Postman                  │              order-service :8081              │
  Bearer JWT + Idempotency-Key ───► │  SecurityConfig (resource server, roles)      │
                                    │  OrderController  /api/v1/orders  @RateLimiter│
                                    │        │                                      │
                                    │  OrderService (no @Transactional around HTTP) │
                                    │        │                    │                 │
                                    │  saveWithEvent()      InventoryClient         │
                                    │  TransactionTemplate: RestClient              │
                                    │   orders row +        timeouts 1s/2s          │
                                    │   outbox_event row    @Retry("inventory")     │
                                    │        │              @CircuitBreaker         │
                                    │  OutboxRelay @Scheduled 1s, SKIP LOCKED       │
                                    └────────┼──────────┬──────────┼────────────────┘
                                             │ JDBC     │ Kafka    │ HTTP/JSON
                                             ▼          │          ▼
                                    ┌────────────────┐  │  ┌──────────────────────────────────────┐
                                    │ Postgres       │  │  │        inventory-service :8082        │
                                    │ db: orders     │  │  │ ReservationController /api/v1/reservations
                                    │ orders,        │  │  │ ProductController     /api/v1/products │
                                    │ outbox_event   │  │  │   @Cacheable("products") ──▶ Redis     │
                                    └────────────────┘  │  │ ReservationService (@Transactional)   │
                                                        │  │   decrementStock: UPDATE ... WHERE     │
         topic orders.events (key = orderRef)           │  │   quantity >= :qty   + @CacheEvict     │
   ┌──────────────────────────────────────────┐         │  └──────────────────┬───────────────────┘
   │ Kafka (KRaft) :9092   + orders.events.DLT │◀────────┘                     │ JDBC
   └──────────────────┬───────────────────────┘                               ▼
                      │ consumer group notification-service        ┌──────────────────────────┐
                      ▼                                            │ Postgres  db: inventory   │
   ┌───────────────────────────────────────────┐                   │ product, reservation      │
   │ notification-service :8083                 │                   └──────────────────────────┘
   │ OrderEventListener @KafkaListener @Transactional
   │   processed_event (dedupe) → "EMAIL -> customer"
   │ DefaultErrorHandler → backoff → DLT        │──▶ Postgres db: notifications (processed_event)
   └───────────────────────────────────────────┘

  All services expose:  /actuator/health/liveness   /actuator/health/readiness   /actuator/prometheus
  Traces → Tempo (OTLP 4318), JSON logs → Loki (Alloy), metrics → Prometheus; Grafana links all three.
```

Points to say about the diagram:

- **Database per service.** order-service never touches the `inventory` DB; the only link is the HTTP API and the shared `orderRef` string. notification-service has its own `notifications` DB with just `processed_event`.
- **One synchronous call** (order → inventory) for the decision that needs an immediate answer; **one asynchronous event stream** (order → Kafka → notification) for side effects. Orchestration for reserve, choreography for notify (see [03](03-microservices.md), [10](10-kafka-event-driven.md)).
- **The `orderRef` is the correlation + idempotency key** across services: generated in `Order.pending(...)` as `UUID.randomUUID()`, stored unique in both `orders.order_ref` and `reservation.order_ref`, and used as the **Kafka message key** so all events of one order stay ordered on one partition. The per-event `eventId` is the consumer's dedupe key.

### Data model

| Table (service) | Important columns / constraints | Why |
|---|---|---|
| `product` (inventory) | `sku unique`, `quantity integer not null check (quantity >= 0)` | DB itself refuses negative stock — last line of defence |
| `reservation` (inventory) | `order_ref unique`, `sku references product(sku)`, `status` RESERVED/RELEASED, `check (quantity > 0)` | unique `order_ref` = idempotency; one reservation per order |
| `orders` (order) | `order_ref unique`, `idempotency_key unique` (nullable), `status`, `failure_reason`, `version bigint`, index `idx_orders_created_at (created_at desc)` | duplicate-POST protection; optimistic lock; fast "newest first" listing |
| `outbox_event` (order) | `id uuid pk`, `aggregate_type`, `aggregate_id` (= orderRef = Kafka key), `event_type`, `payload text` (JSON), `created_at`, `published_at` nullable, index `idx_outbox_pending (published_at, created_at)` | transactional outbox: written in the same tx as the order; relay publishes rows where `published_at is null` oldest-first |
| `processed_event` (notification) | `event_id uuid pk`, `order_ref`, `processed_at` | idempotent consumer: duplicate delivery hits the PK and is skipped |

> Note: the table is `orders`, not `order` — `order` is a reserved SQL word (comment in `Order.java`).
> Simplification: one order = one SKU + quantity (no order lines). Be upfront about it.

### API surface

| Service | Method & path | Success | Errors |
|---|---|---|---|
| order | `POST /api/v1/orders` (+ optional `Idempotency-Key`) — role `customer` | `201 Created` + `Location` (new), `200 OK` (replay) | 400 validation, 401 no/invalid JWT, 403 wrong role, 409 concurrent same key, 429 rate limit (50/s/pod), 503 inventory unavailable (body has `orderId`) |
| order | `GET /api/v1/orders/{id}` — role `customer` or `support` | 200 | 401, 403, 404 |
| order | `GET /api/v1/orders?page=0&size=20` — `customer`/`support` | 200, `PagedModel` (`content` + `page` metadata), newest first | 400 if `size > 100` |
| order | `DELETE /api/v1/orders/{id}` (cancel) — role `customer` | 200 with `CANCELLED` order | 404, 409 not cancellable, 503 inventory down |
| inventory | `GET /api/v1/products`, `GET /api/v1/products/{sku}` (cached, key = sku) | 200 | 404 |
| inventory | `POST /api/v1/reservations` `{orderRef, sku, quantity}` | 201 (also on idempotent replay) | 400, 404 unknown SKU, 409 insufficient stock / concurrent |
| inventory | `DELETE /api/v1/reservations/{orderRef}` | 204 (always, idempotent) | — |

Note: a REJECTED order still returns **201** — the HTTP request succeeded (an order resource was created); the business outcome is in `status` and `failureReason`. Be ready to defend this (see follow-up Q11).

---

## 3. Request flow — placing an order, step by step

`POST /api/v1/orders` with body `{"sku":"PS5-SLIM","quantity":1}`, header `Idempotency-Key: checkout-42` and `Authorization: Bearer <JWT>`.

0. **Authentication + rate limit** — Spring Security's filter chain runs first: the bearer JWT is validated against the issuer's JWKS (signature, `exp`, `iss`) and `JwtRolesConverter` turns `realm_access.roles` / `cognito:groups` into `ROLE_*`; no/invalid token → **401**, a token without `customer` → **403** (test `rejectsRequestsWithoutAValidToken`). Then the Resilience4j `@RateLimiter("orders")` proxy on `OrderController.create` takes a permit (50 per second per pod, `timeout-duration: 0`); none left → `RequestNotPermitted` → **429** ProblemDetail (test `rateLimitExceededIs429`).
1. **Validation** — `OrderController.CreateOrderRequest` is a record with `@NotBlank @Size(max=64) sku` and `@Min(1) @Max(100) quantity`. Because the `Idempotency-Key` header parameter carries `@Size(max=100)`, Spring 6.1+ method validation applies to the whole method, so an invalid body raises `HandlerMethodValidationException` (not `MethodArgumentNotValidException`) → `GlobalExceptionHandler.handleHandlerMethodValidationException` (overridden from `ResponseEntityExceptionHandler`) adds a sorted `errors` list like `["quantity: must be greater than or equal to 1", "sku: must not be blank"]` → **400 ProblemDetail**. `handleMethodArgumentNotValid` is overridden the same way for plain `@Valid @RequestBody` endpoints (e.g. inventory's reservations).
2. **Idempotency check** — `OrderService.placeOrder` calls `orderRepository.findByIdempotencyKey(key)`. If found → return existing order with `created=false` → controller returns **200** with the same body. Inventory is **not called again** (test `idempotencyKeyPreventsDuplicateOrders` verifies exactly 1 POST to WireMock).
3. **Save PENDING** — `Order.pending(sku, qty, key)` generates `orderRef = UUID`, status `PENDING`; `orderRepository.save(...)` commits in its own short transaction (Spring Data repository methods are transactional by default). If two requests with the same key race, the second insert hits the `idempotency_key` unique constraint → `DataIntegrityViolationException` → **409 "Concurrent request"**.
4. **Remote call** — `inventoryClient.reserve(orderRef, sku, qty)`:
   - Proxy order: **Retry( CircuitBreaker( HTTP call ) )** (Resilience4j default aspect order — Retry is outermost).
   - Timeouts: `inventory.connect-timeout: 1s`, `inventory.read-timeout: 2s`.
5. **Inventory side** — `ReservationController.reserve` → `ReservationService.reserve` in **one `@Transactional`**:
   1. `findByOrderRef(orderRef)` → if exists, return it (idempotent replay, still 201).
   2. `productRepository.decrementStock(sku, qty)` → `UPDATE product SET quantity = quantity - :qty WHERE sku = :sku AND quantity >= :qty`.
   3. If 0 rows updated: `existsBySku` false → `ProductNotFoundException` (404); else increment `inventory.reservations.rejected` counter and throw `InsufficientStockException` (409).
   4. Else insert `Reservation(orderRef, sku, qty)` with status RESERVED. If the insert fails (unique `order_ref` race) the whole transaction rolls back, **including the decrement**.
6. **Map the response** in `InventoryClient`:
   - 2xx → return normally → `order.confirm()` → **CONFIRMED**.
   - 409 / 404 → `onStatus(...)` throws `InventoryRejectedException` → `order.reject(msg)` → **REJECTED**. Not retried (not in `retry-exceptions`), not counted by the breaker (`ignore-exceptions`).
   - 5xx (`HttpServerErrorException`) or I/O/timeout (`ResourceAccessException`) → retried up to `max-attempts: 3` with 200ms → 400ms backoff. Still failing → `order.fail("inventory-service unavailable")` → **FAILED**, saved **with an `OrderFailed` outbox event**, counter incremented, `InventoryUnavailableException(orderId)` → **503** with `orderId` property in the problem body.
   - Circuit open → `CallNotPermittedException` immediately (no HTTP call) → same FAILED + 503 path.
7. **Save final state + outbox event + metric** — `OrderService.saveWithEvent(order)`: inside one `TransactionTemplate.execute` it saves the order (optimistic lock via `@Version`) **and** inserts an `OutboxEvent("Order", orderRef, "OrderConfirmed", json)` — both rows commit or neither (transactional outbox, no dual write). `TransactionTemplate` is used instead of `@Transactional` because `saveWithEvent` is a private method called from the same class (self-invocation would bypass the proxy). Then `meterRegistry.counter("orders", "status", STATUS).increment()` → Prometheus `orders_total{status="CONFIRMED"}`.
8. **Response** — `201 Created`, `Location: /api/v1/orders/{id}`, body `OrderResponse` record.
9. **Asynchronously (≤ 1s later)** — `OutboxRelay.publishPending()` (`@Scheduled`, `@Transactional`) selects unpublished rows with `FOR UPDATE SKIP LOCKED`, sends each to Kafka topic `orders.events` with key `orderRef` (`acks=all`, idempotent producer), waits for the ack, marks `published_at`. notification-service's `OrderEventListener` consumes it, skips it if `processed_event` already has the `eventId`, otherwise "sends the email" (a log line) and records the event in the same transaction (test `publishesOrderEventThroughTheOutbox` on the producer side, `processesEachEventExactlyOnceEvenWhenDeliveredTwice` on the consumer side). Trace context travels in Kafka headers, so Tempo shows the whole chain. Details: [10-kafka-event-driven.md](10-kafka-event-driven.md).

### Cancel flow (saga compensation)

`DELETE /api/v1/orders/{id}` → `OrderService.cancel`:

1. Load order (404 if missing).
2. `isCancellable()` is true only for **CONFIRMED** or **FAILED**. Otherwise `OrderNotCancellableException` → 409 (so cancelling twice gives 409, not a second release — test `cancelReleasesReservedStock`).
3. `inventoryClient.release(orderRef)` → `DELETE /api/v1/reservations/{orderRef}` (also retried + breaker-protected).
4. Inventory `ReservationService.release`: if a reservation exists **and** is RESERVED → mark RELEASED and `incrementStock` (and `@CacheEvict(allEntries = true)` on the `products` cache). Unknown or already released → **no-op, 204**. This is what makes it safe to cancel a FAILED order whose real outcome is unknown.
5. `order.cancel()` → `saveWithEvent` (order + `OrderCancelled` outbox row) → counter → notification-service later logs "Order … was cancelled".

Why **FAILED is cancellable**: FAILED means "we don't know" — the request may have timed out *after* inventory committed. Releasing an unknown `orderRef` is a harmless no-op, so cancel is always safe.

---

## 4. Design decisions and trade-offs

### 4.1 Why idempotency in two places?

| Layer | Key | Protects against |
|---|---|---|
| Client → order-service | `Idempotency-Key` header → `orders.idempotency_key unique` | user double-click, mobile app retrying after a network drop, gateway retries → **duplicate orders** |
| order → inventory | `orderRef` → `reservation.order_ref unique` | Resilience4j retrying after a **read timeout where inventory actually succeeded** → **double stock decrement** |

Rule to say: *"Retries are only safe if the receiver is idempotent. I made the receiver idempotent first, then added retries."*

The concurrent-first-request case is handled by the **unique constraint**, not by the `findBy...` check (check-then-act is racy). The loser gets a `DataIntegrityViolationException` → 409 "Concurrent request, retry", and on retry it finds the existing row.

Honest gap: the idempotency key is not tied to a request-body hash. Re-using the same key with a different SKU returns the original order silently. Production-grade APIs (Stripe, the IETF `Idempotency-Key` draft) store a request fingerprint and reject a mismatched reuse with an error (the IETF draft suggests 422). Also keys never expire (a TTL / cleanup job would be needed at scale).

### 4.2 Why atomic `UPDATE ... WHERE quantity >= :qty` instead of locking?

```java
// ProductRepository.java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Product p set p.quantity = p.quantity - :qty where p.sku = :sku and p.quantity >= :qty")
int decrementStock(@Param("sku") String sku, @Param("qty") int qty);
```

| Approach | How | Pros | Cons |
|---|---|---|---|
| Read-modify-write (naive) | `p = find(); p.setQty(p.getQty()-1); save()` | simple | **lost update → overselling** under concurrency |
| Pessimistic lock | `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) | correct | 2 round trips, row lock held for the whole transaction, deadlock risk with multiple rows |
| Optimistic lock | `@Version` + retry on `OptimisticLockException` | no DB locks, good for low contention | hot product (flash sale) → many conflicts → retry storms |
| **Conditional atomic UPDATE (chosen)** | check + decrement in one statement | 1 round trip, row lock held only for that statement, DB re-evaluates the `WHERE` after acquiring the lock, no retries needed | logic lives in SQL; you need the returned row count |

Why it is correct in PostgreSQL: concurrent UPDATEs on the same row are serialised by the row lock; under READ COMMITTED the second updater **re-checks the WHERE clause against the newly committed row**, so `quantity >= :qty` is evaluated on fresh data. Plus the `check (quantity >= 0)` constraint is a safety net.

Proof: `InventoryServiceApplicationTests.concurrentReservationsNeverOversell` — 20 threads, `PS5-SLIM` seeded with 3 → exactly 3 succeed, 17 get `InsufficientStockException`, final stock 0.

`clearAutomatically/flushAutomatically`: bulk JPQL updates bypass the persistence context, so flush pending changes before and clear stale entities after. In `release()` this matters: `r.release()` (dirty entity) is flushed *before* `incrementStock` runs.

Where optimistic locking *is* used: `Order` has `@Version private long version;` — two concurrent updates of the same order (e.g. two cancel calls) → one gets `ObjectOptimisticLockingFailureException` instead of silently overwriting. `GlobalExceptionHandler` maps it to **409 "Concurrent modification"** (covered by `OrderControllerWebTest`, a `@WebMvcTest` slice with a mocked service, since the race is hard to provoke through the full stack).

### 4.3 Why no distributed transaction (2PC / XA)?

- Each service has its own database; XA across HTTP is not possible, and 2PC across DBs is slow, blocks on coordinator failure, and couples availability.
- Instead: **saga-style local transactions + state machine + compensation**.
  - Step 1: order saved PENDING (local tx in order DB).
  - Step 2: reservation (local tx in inventory DB).
  - Step 3: order CONFIRMED/REJECTED/FAILED (local tx).
  - Compensation: cancel → release.
- `OrderService` is deliberately **not** `@Transactional` (see class Javadoc): holding a DB transaction — and a Hikari connection — open during a remote call of up to ~6.6s would exhaust the pool (`maximum-pool-size: 10`) under load, and the remote side effect can't be rolled back anyway.

### 4.4 Why does readiness exclude inventory?

```yaml
# order-service application.yml
management.endpoint.health.group.readiness.include: readinessState,db
```

- Readiness = "should Kubernetes send me traffic?". It should depend only on things that *this pod* can fix by being replaced or waiting — its own DB.
- If inventory were in the readiness group and inventory went down, **every order pod would go NotReady**, the Service would have zero endpoints, and even `GET /orders/{id}` would fail. One outage becomes two (cascading failure).
- Inventory failures are handled at the request level by timeouts/retry/breaker → fast 503 for writes, reads still work.
- Liveness should be even narrower — only "is the JVM stuck?" — never DB or downstream; otherwise a DB blip causes restart loops.
- Test: `readinessDoesNotDependOnInventory` (inventory stubbed to 500, readiness still 200).

### 4.5 Why retry only transient errors?

```yaml
resilience4j.retry.instances.inventory:
  max-attempts: 3
  wait-duration: 200ms
  enable-exponential-backoff: true
  exponential-backoff-multiplier: 2
  retry-exceptions:
    - org.springframework.web.client.ResourceAccessException   # I/O, connect/read timeout
    - org.springframework.web.client.HttpServerErrorException  # 5xx
```

- 409 (out of stock) / 404 (unknown SKU) are **business answers**. Retrying gives the same answer, wastes capacity and adds latency → `InventoryRejectedException`, not retried; also in breaker `ignore-exceptions` so a flood of "out of stock" during a sale doesn't open the circuit.
- Backoff 200ms → 400ms gives the downstream breathing room. **No jitter is configured** (improvement: `randomized-wait-factor` or `IntervalFunction.ofExponentialRandomBackoff` to avoid synchronized retry waves).
- Retries multiply load: 3 attempts × N clients. The circuit breaker caps that.
- Test `rejectsOrderWhenOutOfStockWithoutRetrying` (exactly 1 call on 409) and `retriesTransientInventoryErrors` (503 then 201 → CONFIRMED, 2 calls).

### 4.6 Circuit breaker config

```yaml
resilience4j.circuitbreaker.instances.inventory:
  sliding-window-type: COUNT_BASED
  sliding-window-size: 10
  minimum-number-of-calls: 5
  failure-rate-threshold: 50
  wait-duration-in-open-state: 10s
  permitted-number-of-calls-in-half-open-state: 2
  automatic-transition-from-open-to-half-open-enabled: true
  record-exceptions: [ResourceAccessException, HttpServerErrorException]
  ignore-exceptions: [com.shopflow.order.inventory.InventoryRejectedException]
```

- CLOSED → (≥5 calls and ≥50% failures in last 10) → OPEN for 10s (fail fast with `CallNotPermittedException`) → HALF_OPEN (2 trial calls) → CLOSED or back to OPEN.
- Because Retry wraps the breaker, **each attempt** is recorded. Test `circuitBreakerOpensAndFailsFast`: order 1 = 3 failed attempts, order 2's 2nd attempt is the 5th recorded failure (≥ `minimum-number-of-calls`, 100% ≥ 50%) → OPEN; its 3rd attempt is already rejected with `CallNotPermittedException` (not retried). The test's comment says "6 failures", but only 5 HTTP calls actually reach WireMock. The 3rd order makes **0** HTTP calls.

### 4.7 Latency budget (good senior-sounding point)

Worst case when inventory hangs (accepts connection, never answers): 3 attempts × 2s read timeout + 200ms + 400ms backoff ≈ **6.6s** for one order request (a bit more if connects also time out). Then the breaker opens and calls fail in microseconds. To tighten: lower read timeout, fewer attempts, or a Resilience4j `TimeLimiter`/overall deadline.

### 4.8 Failure modes — what happens in each

| Failure | What the user sees | Order status | Stock | Recovery |
|---|---|---|---|---|
| Invalid request | 400 ProblemDetail with `errors` list | none created | untouched | client fixes input |
| Out of stock / unknown SKU | 201, `status: REJECTED`, `failureReason` | REJECTED | untouched | client retries with new key / different qty |
| Inventory 5xx once, then OK | 201 CONFIRMED (slightly slower) | CONFIRMED | reserved once | automatic (retry) |
| Inventory down / timing out | 503 "Inventory unavailable" with `orderId` | FAILED | **unknown** — maybe reserved if the timeout happened after commit | user/ops cancels → idempotent release |
| Many failures | 503 instantly (breaker OPEN) | FAILED | untouched | breaker half-opens after 10s |
| Read timeout but inventory committed | 503 | FAILED | **reserved (leaked)** | cancel releases it. Gap: no automatic reconciliation |
| Duplicate POST with same key | 200 with original order | unchanged | unchanged | — |
| Two concurrent POSTs, same key | one 201, other 409 "Concurrent request" | one order | reserved once | client retries → gets 200 |
| order-service crashes after saving PENDING, before reserve result | connection error | **stuck PENDING** | maybe reserved | Gap: needs a sweeper job; PENDING is not cancellable today |
| Order DB down | readiness DOWN → pod removed from Service; requests 500 | — | — | K8s routes elsewhere / DB recovers |
| Cancel while inventory down | 503 | stays CONFIRMED/FAILED | still reserved | retry cancel later (release is idempotent) |
| Pod receives SIGTERM | in-flight requests finish (`server.shutdown: graceful`, 20s phase timeout) | — | — | new requests go to other pods |

> Tip: Interviewers love it when you list your own gaps ("stuck PENDING", "FAILED may leak stock") and how you'd fix them. Yeh maturity dikhata hai.

### 4.9 Other small but explainable decisions

- **`ddl-auto: validate` + Flyway** — schema is versioned SQL in Git (`V1__...sql`), reviewed like code, applied the same way in every environment; Hibernate only checks entities match. `update` in prod can silently do partial or destructive changes.
- **`open-in-view: false`** — no DB connection held during JSON serialisation / view rendering; lazy-loading surprises surface as errors in dev instead of hidden N+1 queries in prod. DTO records (`OrderResponse`, `ProductResponse`, `ReservationResponse`) are built inside the service/tx boundary.
- **Records as DTOs** — immutable, no boilerplate; entities are never exposed directly.
- **`@ConfigurationProperties` record** — `InventoryProperties(baseUrl, connectTimeout, readTimeout)` with `Duration` binding (`1s`, `2s`), enabled by `@ConfigurationPropertiesScan` on `OrderServiceApplication`. Env override: `INVENTORY_URL`.
- **Externalised config** — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `INVENTORY_URL` with local defaults (`${DB_URL:jdbc:postgresql://localhost:5432/orders}`) → same jar in every environment (12-factor).
- **Graceful shutdown** — `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`; pairs with the Deployment's `preStop: sleep 5` and `terminationGracePeriodSeconds: 45` (must exceed preStop + shutdown phase: 5 + 20 < 45).
- **Metrics** — `management.metrics.tags.application` tags every metric with the service name; `percentiles-histogram.http.server.requests: true` publishes histogram buckets so Prometheus can compute p95/p99 with `histogram_quantile`.
- **Business metrics** — `orders_total{status=...}` (order-service), `inventory_reservations_rejected_total` (inventory-service), `outbox_unpublished` gauge (order-service), `notifications_sent_total{type}` / `notifications_duplicates_total` (notification-service). Alerts (`monitoring/alert-rules.yml`): 5xx ratio > 5% (HighErrorRate), p99 > 1s excluding `/actuator` (HighP99Latency), `rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="inventory"}[5m]) > 0` (InventoryCircuitOpen), `up == 0` (ServiceDown), `outbox_unpublished > 100` for 5m (OutboxBacklogGrowing — Kafka down or relay stuck), consumer lag > 1000 for 10m (KafkaConsumerLagHigh). A business alert to add: spike in `orders_total{status="FAILED"}`.
- **Tracing** — parent pom brings `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`; `management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}`, `management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:...4318/v1/traces}`; `spring.kafka.template/listener.observation-enabled: true` carries the trace through Kafka. Disabled in tests (`management.otlp.tracing.export.enabled: false`).
- **Virtual threads** — `spring.threads.virtual.enabled: true` in all three services: blocking JDBC/HTTP/Kafka waits no longer pin a platform thread; the DB pool becomes the real concurrency limit (hence explicit `DB_POOL_SIZE`).
- **Validation error details** — both `GlobalExceptionHandler`s override `handleMethodArgumentNotValid` and `handleHandlerMethodValidationException` to add a sorted `errors` list (`"field: message"`) to the ProblemDetail; asserted in `OrderServiceApplicationTests.validatesRequests`.
- **Pagination with limits** — `size` capped at 100 via `@Max(100)` on a `@RequestParam` → 400 for `size=1000` (test `validatesRequests`). Sorted by `createdAt DESC`, backed by index `idx_orders_created_at`. Returns `PagedModel` (stable JSON shape: `content` + `page`).
- **ProblemDetail everywhere** — consistent error JSON; `InventoryUnavailableException` adds an extension property `orderId` so the client can cancel or poll it.
- **`ProductController` has `@Transactional(readOnly = true)` at class level** — read-only hint to Hibernate (no dirty checking / flush). Honest note: usually you put this on a service, not a controller.

---

## 5. Testing story

| Test class | Style | What it proves |
|---|---|---|
| `inventory-service/src/test/.../InventoryServiceApplicationTests.java` (10 tests) | `@SpringBootTest` + `@AutoConfigureMockMvc` + `@AutoConfigureObservability` + profile `test` (H2 `MODE=PostgreSQL`, cache type `simple`) | idempotent reserve, release-only-once, 409/404/400 mapping, **20-thread no-oversell test**, 8 concurrent retries reserve once, **product read cached and evicted on reserve** (`productReadIsCachedAndEvictedOnReserve` via `CacheManager`), probes + `/actuator/prometheus` |
| `order-service/src/test/.../OrderServiceApplicationTests.java` (12 tests) | `@SpringBootTest` + MockMvc + **WireMockServer on a dynamic port** wired via `@DynamicPropertySource` (`inventory.base-url`) + **`@EmbeddedKafka(partitions = 1, topics = "orders.events")`**; every request carries `jwt().authorities("ROLE_customer")` | CONFIRMED path, REJECTED without retry, retry after 503 (WireMock *scenario*), 503 + FAILED after 3 attempts, **breaker opens and makes 0 calls**, Idempotency-Key replay, cancel → DELETE call, validation, pagination, **401 / 403 / open liveness**, **outbox → Kafka record keyed by orderRef** (`publishesOrderEventThroughTheOutbox`), readiness independent of inventory |
| `order-service/src/test/.../OrderControllerWebTest.java` (2 tests) | `@WebMvcTest(OrderController.class)` + `@Import(SecurityConfig, JwtRolesConverter)` + `@MockitoBean JwtDecoder` + `@MockitoBean OrderService` | error mappings hard to provoke end-to-end: optimistic-lock → **409**, `RequestNotPermitted` → **429** |
| `notification-service/src/test/.../NotificationServiceApplicationTests.java` (2 tests) | `@SpringBootTest` + `@EmbeddedKafka(topics = {"orders.events", "orders.events.DLT"})` + Awaitility | same event delivered twice → processed once (`notifications.duplicates` ≥ 1, `notifications.sent` = 1); **poison pill ("this is not json") goes to the DLT and the next event is still processed** |

Test-profile tweaks (`application-test.yml`): H2 URL, `spring.kafka.bootstrap-servers: ${spring.embedded.kafka.brokers}`, a fake `issuer-uri` (never contacted — `jwt()` injects the authentication), `inventory.read-timeout: 500ms`, retry `wait-duration: 10ms`, `outbox.relay.delay: 100ms`, tracing export off → fast tests. 26 tests in total.

Honest limitation: H2 is "Postgres-like", not Postgres. Locking semantics and SQL dialect can differ. Next step: **Testcontainers** with a real `postgres:16` image (and `@ServiceConnection`).

---

## 5b. Deployment & operations (the DevOps half of the story)

| Concern | Where | What to say |
|---|---|---|
| Image | `shopflow/Dockerfile` | 3 stages: Maven build (BuildKit cache mount for `~/.m2`, `-pl ${SERVICE} -am`) → `java -Djarmode=tools ... extract --layers` → `eclipse-temurin:21-jre-alpine` runtime, non-root UID 10001, layers copied dependencies-first so a code change ships a tiny layer, `JarLauncher` entrypoint, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |
| Local stack | `docker-compose.yml` | Postgres 16 with `init-db.sh` creating the three DBs, Kafka `apache/kafka:4.1.0` in KRaft mode (3 default partitions), Redis 7.4 (`allkeys-lru`, 64mb), Keycloak 26.3 with the `shopflow` realm imported; services wait for postgres **and kafka** `service_healthy`, healthchecks on `/actuator/health/readiness`, 512m memory limits, ECS JSON logs; Prometheus + Grafana + Tempo + Loki + Alloy |
| Probes | `k8s/base/*/deployment.yaml` | startupProbe on liveness (2s × 30 = 60s for JVM start), liveness every 10s, readiness every 5s |
| Resources | same | requests `cpu: 250m`, `memory: 384Mi`; limit `memory: 512Mi`; **no CPU limit** on purpose (throttling slows JVM startup and GC) |
| Availability | `hpa.yaml`, `pdb.yaml`, `topologySpreadConstraints` | HPA CPU 70%, PDB `minAvailable: 1` for node drains, replicas spread across nodes (notification-service too) |
| Network | `network-policies.yaml`, `ingress.yaml` | default deny ingress **and egress** (DNS allowed); explicit allow-list per service: inventory only from order-service, Kafka only from order/notification, Redis only from inventory, Keycloak from ingress + order-service (JWKS), notification only from monitoring; egress to Tempo (4318) in `monitoring`; Ingress exposes only public paths |
| Config/secrets | env vars in Deployments, `shopflow-endpoints` ConfigMap, `shopflow-db` + `keycloak-admin` Secrets | endpoints ConfigMap holds `kafka-bootstrap-servers`, `redis-host`, `jwt-issuer-uri`, `otlp-endpoint` (dev: in-cluster names; prod: `behavior: replace` with MSK / ElastiCache / Cognito / collector + `rds-endpoint`); dev secrets via `secretGenerator`; prod synced from AWS Secrets Manager by External Secrets Operator |
| Environments | `k8s/overlays/dev`, `k8s/overlays/prod` | dev: 1 replica, `:dev` images, `imagePullPolicy: IfNotPresent`; prod: 3–10 replicas, GHCR images pinned to the git SHA by CI, TLS host `shop.example.com`, **`$patch: delete` removes the in-cluster Postgres/Kafka/Redis/Keycloak and their NetworkPolicies**, adds egress to the VPC CIDR (5432/9092/9098) and 443 for Cognito JWKS |
| Database / broker / cache / IdP | `k8s/base/{postgres,kafka}/statefulset.yaml`, `redis/`, `keycloak/` | demo single-replica StatefulSets (Postgres with three logical DBs, Kafka KRaft combined node with a 2Gi PVC), Redis Deployment with no volume ("losing it only costs cache misses"), Keycloak Deployment with realm from a ConfigMap; prod = RDS / MSK / ElastiCache / Cognito from `infra/terraform/aws` |

> Tip: Interviewer DevOps side pooche toh "image → compose → k8s → CI → alerts" order me bolo. Har step ka ek reason ready rakho (non-root kyun, CPU limit kyun nahi, preStop kyun).

---

## 6. What would you improve next? (have 3-4 ready)

| Improvement | Why | How (concretely) |
|---|---|---|
| **Event-driven reservation** | Today order→inventory is still synchronous; an inventory outage = failed orders. The outbox + Kafka already exist for notifications; extending them to the reservation would decouple availability. | Emit `OrderPlaced` from the PENDING save; inventory consumes, reserves idempotently (it already dedupes on `orderRef`), publishes `StockReserved`/`StockRejected`; order consumes and updates status. Cost: the client must poll a PENDING order. |
| **Reconciliation / sweeper job** | fixes stuck PENDING and leaked FAILED reservations | `@Scheduled` (or K8s CronJob) finds PENDING/FAILED older than N minutes → re-call `reserve` (idempotent) to learn the truth, or release. Make PENDING cancellable. |
| **Authorization hardening** | any `customer` can read/cancel any order; `aud` not validated; inventory/notification have no app-level auth | store the JWT `sub` on the order and check ownership (`@PreAuthorize` — `@EnableMethodSecurity` is already on); add audience validation; client-credentials tokens or a service mesh (mTLS) between services. |
| **Distributed rate limiting at the edge** | today only an NGINX Ingress does path routing; the Resilience4j limit is **per pod** (50 rps × N pods) and not per user | NGINX Ingress annotations (`limit-rps`) or Spring Cloud Gateway / Kong with a Redis token bucket per user; add `Retry-After`. |
| **Redis cache test against a real Redis** | the JDK-serialization trap is already closed: `inventory-service/config/CacheConfig.java` (`RedisCacheManagerBuilderCustomizer` + `GenericJackson2JsonRedisSerializer`) stores values as JSON and `CacheSerializationTest` pins the round-trip — but no test exercises `@Cacheable` against a real Redis | Testcontainers Redis profile running `productReadIsCachedAndEvictedOnReserve`. |
| **Schema Registry / contract tests** | the event contract is a copied record with `ignoreUnknown`; a rename would silently break the consumer | Avro/JSON Schema + registry with BACKWARD compatibility in CI, or a shared contract test; `aws-msk-iam-auth` client config for MSK. |
| **Testcontainers** | real Postgres/Kafka/Redis semantics in tests | `@Testcontainers` + `PostgreSQLContainer` + `@ServiceConnection`. |
| **Idempotency hardening** | same key + different body; keys never expire | store request hash, 422 on mismatch; TTL cleanup. |
| **Jitter on retries, bulkhead** | avoid thundering herd; isolate inventory call threads | `randomized-wait-factor`; Resilience4j `@Bulkhead`. |
| **Outbox housekeeping** | published rows are never deleted; polling adds ~1s latency | archive/delete job for `published_at < now() - 7d`; move to Debezium CDC when volume grows. |
| **Exemplars + alert on DLT** | traces and metrics are linked only via logs today; nobody is alerted on `orders.events.DLT` | enable exemplar storage in Prometheus; alert on DLT message count / consumer-group lag on the DLT. |
| **Multi-line orders** | realistic carts | `order_line` table, reserve multiple SKUs in one inventory tx (sort SKUs to avoid deadlocks). |

---

## 7. Likely follow-up questions (crisp answers)

**Q1. Why three services and not a monolith?**
To practise real distributed-system problems: network failures, partial failure, idempotency, and data ownership. Inventory and orders also have different scaling and change patterns. For a real 3-person startup I'd honestly start with a modular monolith and split when needed.

**Q2. How do you guarantee no overselling?**
A single conditional `UPDATE product SET quantity = quantity - :qty WHERE sku = :sku AND quantity >= :qty`. The row lock serialises concurrent updates and the WHERE is re-checked on the latest committed row. If 0 rows updated → 409. The `CHECK (quantity >= 0)` constraint is a backstop. Proven by a 20-thread test.

**Q3. What if the same reservation request arrives twice at the same time?**
Both may miss `findByOrderRef`, both decrement, but only one insert passes the `order_ref` unique constraint. The loser's transaction rolls back **including its decrement** (same `@Transactional`). `ReservationController` catches that `DataIntegrityViolationException` *outside* the transaction and returns the **winner's reservation with 201**, so both callers see the same answer and stock moves once (`concurrentRetriesOfTheSameOrderReserveOnce`: 8 parallel requests, 8 × 201, stock −1). Why in the controller and not inside `reserve()`? Catching inside the `@Transactional` method would leave the transaction marked rollback-only and still fail at commit. Earlier version returned 409 here, which order-service would have mis-read as "insufficient stock" → REJECTED although stock was reserved; a good example of why a 409 must mean exactly one thing per endpoint.

**Q4. Inventory timed out but actually reserved the stock. Now what?**
Order is FAILED and the client gets 503 with the `orderId`. The reservation exists. Since `release` is idempotent, cancelling the FAILED order frees the stock. Automatic fix would be a reconciliation job that retries `reserve` with the same `orderRef` — inventory returns the existing reservation, so we learn it succeeded and can mark CONFIRMED.

**Q5. Why not put `@Transactional` on `placeOrder`?**
A remote call inside a transaction holds a DB connection for up to ~6.6s; with a pool of 10, ten slow orders block the whole service. Also the remote reservation can't be rolled back by our DB rollback, so the transaction gives false safety. Instead every state change is a short separate save and the status tells us where we stopped.

**Q6. Why is Retry outside the CircuitBreaker?**
Resilience4j's default aspect order is `Retry(CircuitBreaker(...))`. Every attempt is recorded by the breaker, so it opens quickly when the downstream is dead, and once open, the `CallNotPermittedException` is not in `retry-exceptions`, so we fail fast instead of retrying an open circuit.

**Q7. Why don't 409s open the circuit breaker?**
They're healthy responses from a healthy service. `InventoryRejectedException` is in `ignore-exceptions`. Otherwise a flash sale where stock runs out would "open" the circuit and block orders for other in-stock products.

**Q8. Which HTTP errors are retried?**
Only `ResourceAccessException` (connection refused, timeouts) and `HttpServerErrorException` (5xx). Never 4xx. And retrying POST is only safe because the reservation is idempotent by `orderRef`.

**Q9. What is the `Idempotency-Key` flow, and what happens if the client sends none?**
With key: lookup → replay returns 200 with the original order. Without key: every POST creates a new order (the header is `required = false`). In production I'd make it mandatory for checkout clients or have the gateway generate one.

**Q10. How does optimistic locking help in order-service?**
`@Version` on `Order`. Hibernate adds `WHERE version = ?` to updates; if another transaction updated the row first, 0 rows → `ObjectOptimisticLockingFailureException`. Prevents lost updates like a cancel racing with a status update.

**Q11. Why return 201 for a REJECTED order instead of 409?**
The order resource *was* created and persisted with an id, and the client can GET it later. The business outcome is in the body. Alternative design: return 409/422 and not persist rejected orders. Both are defensible — what matters is consistency and documentation (OpenAPI).

**Q12. How would you scale this?**
Services are stateless → horizontal pod scaling. Already configured: HPA on CPU at 70% of the request (`cpu: 250m`), 2–6 pods in base, 3–10 in the prod overlay, 5-minute scale-down stabilisation. DB: pool size × pods must stay under Postgres `max_connections`: the Deployments set `DB_POOL_SIZE=5`, so 2 services × 10 pods (HPA max) × 5 = 100 connections, and the StatefulSet runs Postgres with `max_connections=200` (at real scale: PgBouncer). Hot SKUs: the row lock on one product becomes the bottleneck — options are stock sharding (split stock across N rows), queue-based reservation, or Redis atomic `DECRBY` with DB reconciliation.

**Q13. How do you monitor it in production?**
Prometheus scrapes `/actuator/prometheus` on all three services. Dashboards: RED metrics from `http_server_requests_seconds` (rate, errors, p95 via histogram buckets), `orders_total` by status, `inventory_reservations_rejected_total`, `resilience4j_circuitbreaker_state`, `outbox_unpublished`, Kafka consumer lag, `notifications_sent_total`, cache hit ratio, Hikari pool metrics (`hikaricp_connections_active/pending`), JVM memory/GC. Alerts in `monitoring/alert-rules.yml`: HighErrorRate (5xx > 5% for 5m, page), HighP99Latency (> 1s for 10m, ticket), InventoryCircuitOpen (page), ServiceDown, OutboxBacklogGrowing (page), KafkaConsumerLagHigh (ticket). Traces go over OTLP to Tempo (sampling `TRACING_SAMPLE`), logs are ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) shipped by Alloy to Loki, and Grafana links a span to its log lines and a `trace.id` in a log line back to the trace.

**Q14. What does graceful shutdown actually do?**
On SIGTERM, Tomcat stops accepting new connections, waits up to 20s for in-flight requests, then the context closes (pools closed). In K8s, readiness goes to refusing traffic too. The Deployments have `preStop: sleep 5` so the pod is removed from Service/Ingress endpoints *before* SIGTERM, and `terminationGracePeriodSeconds: 45` so K8s doesn't SIGKILL during the 20s drain. Rolling updates use `maxUnavailable: 0`, `maxSurge: 1`.

**Q15. Why Flyway + `ddl-auto=validate`?**
Schema changes are versioned, reviewable and repeatable; the app fails fast at startup if entities and schema disagree. Rule: never edit an applied migration — add `V3__...`. For zero-downtime: expand/contract (add nullable column → deploy → backfill → make NOT NULL later).

**Q16. How did you test the resilience logic without a real inventory service?**
WireMock on a random port, injected via `@DynamicPropertySource`. I stub 409 (expect 1 call, REJECTED), a scenario 503→201 (expect 2 calls, CONFIRMED), constant 500 (expect 3 calls, FAILED + 503), and assert the breaker is OPEN and the next call makes 0 HTTP requests. The breaker is reset in `@BeforeEach` so tests are independent.

**Q17. Why H2 in tests — isn't that risky?**
It's fast and needs no Docker, and `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` covers the dialect basics; Flyway runs the same migrations. Risk: locking/isolation differences. Next step is Testcontainers with real Postgres, at least for the concurrency test.

**Q18. Is there a security layer?**
Yes, in order-service: Spring Security as an **OAuth2 resource server** (`spring-boot-starter-oauth2-resource-server`). Requests carry a JWT issued by Keycloak (compose/minikube, realm `shopflow`, users `alice`=customer, `bob`=support) or Amazon Cognito (prod); Spring validates signature via the issuer's JWKS, `exp` and `iss`; `JwtRolesConverter` maps `realm_access.roles` or `cognito:groups` to `ROLE_*`. Rules: GET orders → `customer` or `support`, writes → `customer`, actuator/swagger open (network-restricted), everything else denied; stateless, CSRF off (bearer tokens, no cookies). Tests inject fake JWTs with `jwt().authorities(...)`. Infrastructure security on top: NetworkPolicies (default deny ingress + egress; inventory only from order-service; Kafka only from order/notification; Redis only from inventory), non-root UID 10001, `readOnlyRootFilesystem`, all capabilities dropped, `automountServiceAccountToken: false`, Secrets (External Secrets in prod), Trivy + Dependabot. Honest gaps: no ownership check (any customer can read any order), `aud` not validated, inventory/notification rely on network isolation only. Details: [11-security-caching-performance.md](11-security-caching-performance.md).

**Q19. Is there service discovery / a gateway / Kafka / tracing / caching?**
Kafka: yes — transactional outbox in order-service → `orders.events` → notification-service (idempotent consumer, DLT). Tracing: yes — Micrometer Tracing + OTel bridge, OTLP to Tempo, trace ids in JSON logs in Loki, propagated over HTTP and Kafka headers. Caching: yes — Spring Cache on `GET /products/{sku}` with Redis in compose/K8s, evicted on reserve/release. A real API gateway is **not** in the project: discovery is plain Kubernetes DNS (`INVENTORY_URL=http://inventory-service:8082`, no Eureka) and the edge is an NGINX Ingress (`shopflow.local`, TLS via cert-manager in prod) routing only `/api/v1/orders` and `/api/v1/products` — `/api/v1/reservations` is deliberately not exposed publicly. Rate limiting exists but per pod inside order-service, not at the edge.

**Q20. What was the hardest bug / most interesting part?**
Good answer: the `clearAutomatically`/`flushAutomatically` detail in `release()` — the bulk `incrementStock` update bypasses the persistence context, so the `RELEASED` status change on the managed `Reservation` must be flushed before the context is cleared, otherwise the status change is lost and a second release would add stock twice. The `releaseRestoresStockOnlyOnce` test guards it.

**Q21. What's the response time impact of all this resilience?**
Happy path: none (one HTTP call). Transient failure: +200ms/+400ms backoff. Dead downstream: first few requests up to ~6.6s, then the breaker makes failures instant. That's the trade-off you tune with timeouts and attempts.

**Q22. How would you deploy it?**
CI (`.github/workflows/shopflow.yml`): `./mvnw -B verify` → `kustomize build | kubeconform -strict` for every overlay + `promtool check rules` + `docker compose config` → `terraform fmt -check` + `validate` on `infra/terraform/aws` → build each image from the shared `Dockerfile` (matrix over the three services, GHA layer cache) → Trivy scan failing on fixable HIGH/CRITICAL → push to GHCR tagged with the commit SHA (and `latest`) on `main` only → `deploy-manifests` job runs `kustomize edit set image` for all three services in `k8s/overlays/prod` and commits the SHA back (GitOps). Argo CD (`shopflow/argocd/application-prod.yaml`: automated sync, prune, selfHeal, `ignoreDifferences` on replicas) applies the prod overlay; dev is synced by hand from `application-dev.yaml`. The same stages exist as a declarative `shopflow/Jenkinsfile`. Kubernetes: Deployments with startup/liveness/readiness probes on the actuator endpoints, Secret `shopflow-db` for passwords (secretGenerator in dev, External Secrets in prod), endpoints ConfigMap for Kafka/Redis/issuer/OTLP, HPA, PDB `minAvailable: 1`, topology spread across nodes, Prometheus scrape annotations.

---

## 8. One-page cheat sheet

```
PITCH     3 services, DB-per-service, sync REST for reserve + outbox/Kafka for notify, saga states, idempotent everything.
OVERSELL  UPDATE ... WHERE quantity >= :qty   (+ CHECK quantity >= 0, + 20-thread test)
IDEMP     Idempotency-Key -> orders.idempotency_key UNIQUE ; orderRef -> reservation.order_ref UNIQUE ; eventId -> processed_event PK
RETRY     3 attempts, 200ms x2 backoff, only ResourceAccessException + 5xx, no jitter (yet)
BREAKER   count window 10, min 5 calls, 50% -> OPEN 10s -> HALF_OPEN 2 calls; ignores business 409/404
TIMEOUTS  connect 1s, read 2s  -> worst case ~6.6s before breaker opens
STATES    PENDING -> CONFIRMED | REJECTED | FAILED ;  CONFIRMED|FAILED -> CANCELLED (release)
NO TX     no @Transactional around HTTP (pool of 10 would starve); saveWithEvent = short TransactionTemplate (order + outbox row)
OUTBOX    outbox_event -> OutboxRelay @Scheduled 1s, FOR UPDATE SKIP LOCKED, batch 100, acks=all, key=orderRef -> orders.events
CONSUMER  group notification-service, auto-commit off, processed_event dedupe, ExponentialBackOff 0.5s x2 <=10s -> orders.events.DLT
AUTH      resource server, JWT RS256 via JWKS, stateless, CSRF off; GET customer|support, writes customer; Keycloak local / Cognito prod
CACHE     @Cacheable("products") DTO by sku, @CacheEvict on reserve/release, simple|redis (TTL 60s, allkeys-lru)
LIMIT     @RateLimiter("orders") 50/s/pod, timeout 0 -> 429 ProblemDetail
TRACE     micrometer-tracing-bridge-otel + OTLP -> Tempo; trace.id in ECS logs -> Loki (Alloy); Kafka headers carry traceparent
THREADS   spring.threads.virtual.enabled=true (all 3); DB pool is the real limit (DB_POOL_SIZE=5 in K8s)
PROBES    readiness = readinessState + db (NOT inventory) ; liveness separate
METRICS   orders_total{status}, inventory_reservations_rejected_total, outbox_unpublished, notifications_sent_total, http histograms
ALERTS    HighErrorRate, HighP99Latency, InventoryCircuitOpen, ServiceDown, OutboxBacklogGrowing, KafkaConsumerLagHigh
DEPLOY    layered non-root image, compose (+Kafka/Redis/Keycloak/Tempo/Loki), Kustomize dev/prod, HPA, PDB, NetPol, Ingress; prod = RDS/MSK/ElastiCache/Cognito
CI/CD     verify -> kubeconform/promtool/terraform validate -> build x3 -> Trivy -> push GHCR (sha) -> pin prod overlay -> Argo CD
GAPS      stuck PENDING, FAILED may leak stock, no ownership check / aud, per-pod rate limit, H2 not real PG, no jitter
NEXT      event-driven reservation, sweeper job, @PreAuthorize ownership, edge rate limit, Schema Registry, Testcontainers
```
