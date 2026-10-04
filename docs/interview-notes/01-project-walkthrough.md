# 01 — ShopFlow Project Walkthrough (How to Explain It in an Interview)

> Source of truth: `shopflow/` in this repo. Every class name, file path and config key below exists in the code.
> If an interviewer asks about something not listed here, say honestly "not in this project — this is how I would add it".

---

## 0. Quick facts (memorise this table)

| Item | Value |
|---|---|
| Build | Maven multi-module: `shopflow/pom.xml` (parent) → `inventory-service`, `order-service`, `notification-service`, `payment-service` + library module `shopflow-outbox` (package `com.shopflow.outbox`, used by order- and payment-service) |
| Stack | Spring Boot **3.5.x**, Java **21** (virtual threads on in all four), PostgreSQL, Flyway, Spring Data JPA (Hibernate), Spring Kafka |
| Resilience | Resilience4j **2.4** (`resilience4j-spring-boot3` + `spring-boot-starter-aop`) — only in order-service: retry, circuit breaker, **rate limiter** (`@RateLimiter("orders")`, 50 rps/pod → 429) |
| Messaging | **Transactional outbox** as a shared module (`shopflow-outbox`: `OutboxEvent` with a `topic` column, `OutboxRepository` `FOR UPDATE SKIP LOCKED`, `OutboxRelay` `@Scheduled` sends to `event.getTopic()`, nightly `cleanup` deletes published rows older than 7d, `OutboxPublisher.publish(topic, aggregateType, aggregateId, eventType, event)`) → topic `orders.events` (key `orderRef`, `acks=all`, idempotent producer) → **notification-service** (group `notification-service`, `processed_event`) and **payment-service** (group `payment-service`, idempotent via `payment` PK = `eventId`) → topic `payments.events` → **order-service** `PaymentEventListener` (group `order-service`, `processed_event` V5). DLTs `orders.events.DLT`, `payments.events.DLT`. Saga = orchestration (REST reserve) + choreography (payment, notification) |
| Security | order-service is an **OAuth2 resource server** (stateless JWT; Keycloak locally, Cognito in prod; `JwtRolesConverter` maps `realm_access.roles` / `cognito:groups` → `ROLE_*`; `JwtDecoderConfig`: `SupplierJwtDecoder` (lazy issuer metadata) + optional **audience validation** `jwt.audience` = `${JWT_AUDIENCE:}` accepting `aud` / `azp` (Keycloak) / `client_id` (Cognito)); **object-level authorization**: `Order.customerId` = JWT `sub`, `Caller` record (`customerId`, `support`), owner-or-support check in `OrderService` (someone else's order → 404, list scoped by `findByCustomerId`); inventory/notification/payment have no app auth (NetworkPolicy only) |
| Caching | inventory-service `@EnableCaching`; `@Cacheable("products")` on `GET /products/{sku}` (DTO), `@CacheEvict` in `reserve`/`release`; `CACHE_TYPE=simple` default, `redis` in compose/K8s, TTL 60s; **fail-open**: `CacheConfig implements CachingConfigurer` → `LoggingCacheErrorHandler` (Redis down = logged cache miss, request falls through to the DB) |
| HTTP client | Spring `RestClient` with `SimpleClientHttpRequestFactory` (connect 1s, read 2s) |
| API docs | springdoc-openapi → `/swagger-ui.html` |
| Observability | Actuator (`health`, `info`, `prometheus` exposed), Micrometer Prometheus registry, custom counters; **tracing** via `micrometer-tracing-bridge-otel` + OTLP exporter → Tempo; ECS JSON logs → Loki (Alloy); Grafana links traces↔logs |
| Error format | RFC 7807 `ProblemDetail` (`application/problem+json`) via `@RestControllerAdvice` (incl. 429 for rate limit) |
| Tests | `@SpringBootTest` + `MockMvc`, H2 in `MODE=PostgreSQL`, WireMock standalone for inventory, `@EmbeddedKafka`, `spring-security-test` `jwt()`; one `@WebMvcTest` slice, **ArchUnit** (`archunit-junit5` 1.5.1). Counts: inventory **11** (10 + `CacheSerializationTest`), order **29** (`OrderServiceApplicationTests` 17, `OrderControllerWebTest` 2, `OutboxCleanupTest` 1, `ArchitectureTest` 7, `AudienceValidatorTest` 2), notification **2**, payment **4** = **46** |
| Ports | order-service **8081**, inventory-service **8082**, notification-service **8083**, payment-service **8084**, Keycloak **8180** |
| Packaging | `<finalName>app</finalName>` → `target/app.jar`; `build-info` goal → version at `/actuator/info` |
| Container | one multi-stage, layered, non-root `shopflow/Dockerfile` (`--build-arg SERVICE=...`), `MaxRAMPercentage=75` |
| Local stack | `shopflow/docker-compose.yml`: postgres + Kafka (KRaft) + Redis + Keycloak + four services + Prometheus + Grafana + Tempo + Loki + Alloy |
| Kubernetes | `shopflow/k8s/` Kustomize base + `overlays/dev` / `overlays/prod`: Deployments, Services, HPA, PDB, Ingress, NetworkPolicies, Postgres/Kafka StatefulSets, Redis + Keycloak Deployments, `shopflow-endpoints` ConfigMap; prod overlay **deletes** Postgres/Kafka/Redis/Keycloak in favour of RDS/MSK/ElastiCache/Cognito (`infra/terraform/aws`). Also a generic **Helm chart** `shopflow/helm/shopflow-service` (deployment/service/hpa/pdb/serviceaccount, one values file per service in `helm/values/`) as the Kustomize-vs-Helm comparison |
| CI/CD | `.github/workflows/shopflow.yml`: `./mvnw -B verify` → kustomize + kubeconform + promtool → Terraform fmt/validate → image build (4 services) → Trivy scan → CycloneDX **SBOM** (`anchore/sbom-action`) → push to GHCR on `main` → keyless **cosign** signature (OIDC `id-token`) → `k6 inspect` on `loadtest/flash-sale.js` → `deploy-manifests` pins the SHA into `overlays/prod` → Argo CD (`shopflow/argocd/`) syncs. `shopflow/Jenkinsfile` = same pipeline for Jenkins |
| Alerts | `shopflow/monitoring/alert-rules.yml`: HighErrorRate, HighP99Latency, InventoryCircuitOpen, ServiceDown, **OutboxBacklogGrowing**, **KafkaConsumerLagHigh**, **PaymentFailureRateHigh** (`payments_total{status="FAILED"}` ratio > 0.5 for 10m) |
| Load test | `shopflow/loadtest/flash-sale.js` (k6): 300 buyers for 3 PS5 + 200 rps browse; thresholds `orders_confirmed count==3`, order p95 < 800ms, browse p99 < 300ms, `http_req_failed` < 1% |

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
│       ├── order/OrderStatus.java            # PENDING, CONFIRMED, PAID, REJECTED, FAILED, CANCELLED
│       ├── inventory/InventoryClient.java    # RestClient + @Retry + @CircuitBreaker
│       ├── inventory/InventoryProperties.java # @ConfigurationProperties(prefix="inventory") record
│       ├── inventory/InventoryRejectedException.java # business "no" — never retried
│       ├── outbox/OrderEvent.java            # event contract, TOPIC = "orders.events" (outbox classes moved to shopflow-outbox)
│       ├── payment/PaymentEventListener.java # saga step 3: consumes payments.events (group order-service), processed_event dedupe
│       ├── payment/PaymentEvent.java         # consumer-side copy of the payments.events contract
│       ├── payment/ProcessedEvent*.java, ProcessedEventCleanup.java  # inbox table + daily 7d cleanup
│       ├── payment/KafkaErrorConfig.java     # backoff → payments.events.DLT
│       ├── security/SecurityConfig.java      # resource server, stateless, role rules
│       ├── security/JwtRolesConverter.java   # realm_access.roles | cognito:groups → ROLE_*
│       ├── security/JwtDecoderConfig.java    # SupplierJwtDecoder + audience validator (aud | azp | client_id)
│       ├── JpaConfig.java                    # @EntityScan/@EnableJpaRepositories for order + outbox packages (NOT on the app class)
│       └── common/GlobalExceptionHandler.java # 404 / 409 / 429 / 503 ProblemDetail
│   └── src/main/resources/db/migration/V1__create_orders.sql, V2__outbox.sql, V3__order_owner.sql, V4__outbox_topic.sql, V5__processed_event.sql
│   └── src/test/java/com/shopflow/order/architecture/ArchitectureTest.java   # ArchUnit rules (7)
├── shopflow-outbox/                          # library module, package com.shopflow.outbox
│   └── OutboxEvent.java (topic column), OutboxRepository.java (SKIP LOCKED, deletePublishedBefore),
│       OutboxRelay.java (send to event.getTopic(), nightly cleanup), OutboxPublisher.java (publish(topic, aggregateType, aggregateId, eventType, event))
├── payment-service/                          # :8084, DB payments
│   └── src/main/java/com/shopflow/payment/
│       ├── OrderConfirmedListener.java       # consumes orders.events (group payment-service), ignores other types, PK = eventId
│       ├── Payment.java, PaymentRepository.java  # payment(event_id PK, order_ref unique, amount, status CAPTURED|FAILED)
│       ├── PaymentGateway.java, FakePaymentGateway.java  # declines when amount > payment.card-limit (100000)
│       ├── PriceList.java, PaymentEvent.java # demo prices; PaymentCaptured / PaymentFailed on payments.events
│       ├── KafkaErrorConfig.java, JpaConfig.java
│   └── src/main/resources/db/migration/V1__payment.sql
└── notification-service/
    └── src/main/java/com/shopflow/notification/
        ├── OrderEventListener.java           # @KafkaListener + @Transactional, idempotent via processed_event
        ├── ProcessedEvent.java, ProcessedEventRepository.java
        ├── KafkaErrorConfig.java             # DefaultErrorHandler + ExponentialBackOff → orders.events.DLT
        └── OrderEvent.java                   # copy of the contract, @JsonIgnoreProperties(ignoreUnknown)
    └── src/main/resources/db/migration/V1__processed_event.sql
├── Dockerfile, .dockerignore, docker-compose.yml, Jenkinsfile, argocd/
├── k8s/base/{order-service,inventory-service,notification-service,payment-service,postgres,kafka,redis,keycloak}/,
│   endpoints-configmap.yaml, ingress.yaml, network-policies.yaml
├── k8s/overlays/{dev,prod}/kustomization.yaml (+ prod/external-secret.yaml, prod/service-accounts.yaml)
├── helm/shopflow-service/ (generic chart), helm/values/*.yaml, helm/README.md (Kustomize vs Helm)
├── loadtest/flash-sale.js (k6)
└── monitoring/prometheus.yml, alert-rules.yml, tempo.yml, alloy.river, grafana/provisioning/
(.github/workflows/shopflow.yml and infra/terraform/aws/ at repo root)
```

---

## 1. The 60-second pitch

Say this almost word for word, then stop and let them ask questions.

> "ShopFlow is a small but production-style e-commerce backend with four Spring Boot 3.5 / Java 21 microservices, each with its own PostgreSQL database managed by Flyway, plus a shared outbox library.
>
> **inventory-service** owns product stock. It exposes a reservation API. Reservations are **idempotent by `orderRef`**, and the stock decrement is a **single atomic SQL `UPDATE ... WHERE quantity >= :qty`**, so we can never oversell — I have a concurrency test where 20 threads fight over 3 PS5s and exactly 3 win. Product reads are cached with Spring Cache (Redis in compose/K8s) and evicted on every reservation.
>
> **order-service** accepts orders behind **OAuth2 / JWT** (Keycloak locally, Cognito on AWS) with a per-pod **rate limiter**. It calls inventory over HTTP with `RestClient`, with **connect/read timeouts, Resilience4j retry with exponential backoff, and a circuit breaker**. Clients can send an **`Idempotency-Key`** header so a retried POST never creates a duplicate order. There is no distributed transaction: each order moves through states — PENDING, CONFIRMED, PAID, REJECTED, FAILED, CANCELLED. Reserving stock is **orchestrated** synchronously by order-service; payment is **choreographed**: **payment-service** reacts to the `OrderConfirmed` event, charges through a gateway interface, and publishes `PaymentCaptured` or `PaymentFailed`, which order-service consumes to mark the order PAID or to release the stock and cancel it — the **saga compensation**. Every final state change writes an event into a **transactional outbox** in the same DB transaction; a relay publishes it to **Kafka** (`orders.events`, keyed by `orderRef`).
>
> **notification-service** consumes the same order events and sends the customer notification. It is an **idempotent consumer** (a `processed_event` table in the same transaction as the side effect), and poison messages are retried with backoff and moved to a **dead-letter topic**.
>
> Operationally all services have RFC 7807 error responses, Bean Validation, Kubernetes liveness/readiness probes, Prometheus metrics including business counters, **distributed tracing** (Micrometer → OTLP → Tempo, with trace ids in JSON logs shipped to Loki), graceful shutdown and OpenAPI docs. Tests run the full Spring context with MockMvc, H2 in Postgres mode, an embedded Kafka broker, fake JWTs and WireMock to simulate inventory failures like 503s and outages; ArchUnit rules keep the layering honest.
>
> It ships as a layered non-root Docker image, runs locally with Docker Compose plus Kafka, Redis, Keycloak and the Grafana stack, and deploys to Kubernetes with Kustomize overlays — HPA, PodDisruptionBudget, NetworkPolicies, Ingress — through a GitHub Actions pipeline that tests, validates manifests and Terraform, scans the images with Trivy, attaches a CycloneDX SBOM, signs them keyless with cosign, pushes to GHCR and pins the SHA for Argo CD. There is also a generic Helm chart and a k6 flash-sale load test whose thresholds encode 'exactly 3 PS5s sold'. The prod overlay swaps the in-cluster Postgres/Kafka/Redis/Keycloak for RDS, MSK, ElastiCache and Cognito provisioned by Terraform."

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
   │ Kafka (KRaft) :9092  orders.events (+DLT) │◀────────┘                     │ JDBC
   │                      payments.events (+DLT)│
   └──────────────────┬───────────────────────┘                               ▼
                      │ groups notification-service + payment-service  ┌──────────────────────────┐
                      ▼                                            │ Postgres  db: inventory   │
   ┌───────────────────────────────────────────┐                   │ product, reservation      │
   │ notification-service :8083                 │                   └──────────────────────────┘
   │ OrderEventListener @KafkaListener @Transactional
   │   processed_event (dedupe) → "EMAIL -> customer"
   │ DefaultErrorHandler → backoff → DLT        │──▶ Postgres db: notifications (processed_event)
   └───────────────────────────────────────────┘
                      │ consumer group payment-service (same topic, own offsets)
                      ▼
   ┌───────────────────────────────────────────┐
   │ payment-service :8084                      │
   │ OrderConfirmedListener (OrderConfirmed only)│
   │   PriceList × qty → PaymentGateway.charge  │──▶ Postgres db: payments (payment PK = eventId, outbox_event)
   │   payment row + outbox row in ONE tx       │
   │ OutboxRelay (shopflow-outbox) → payments.events (key = orderRef)
   └───────────────────┬───────────────────────┘
                       │ consumer group order-service
                       ▼
   order-service PaymentEventListener (processed_event dedupe):
     PaymentCaptured → OrderService.onPaymentCaptured → PAID
     PaymentFailed   → OrderService.onPaymentFailed   → inventoryClient.release + CANCELLED("payment failed: <reason>")

  All services expose:  /actuator/health/liveness   /actuator/health/readiness   /actuator/prometheus
  Traces → Tempo (OTLP 4318), JSON logs → Loki (Alloy), metrics → Prometheus; Grafana links all three.
```

Points to say about the diagram:

- **Database per service.** order-service never touches the `inventory` DB; the only link is the HTTP API and the shared `orderRef` string. notification-service has its own `notifications` DB with just `processed_event`; payment-service has `payments` (`payment` + its own `outbox_event`).
- **One synchronous call** (order → inventory) for the decision that needs an immediate answer; **two asynchronous event streams** (`orders.events` fan-out to notification + payment, `payments.events` back to order). Orchestration for reserve, **choreography for payment and notification**: nobody tells payment-service what to do, it reacts to `OrderConfirmed`, and order-service reacts to its answer (see [03](03-microservices.md), [10](10-kafka-event-driven.md)).
- **The shared outbox module** (`shopflow-outbox`) is plain code, not a service: each host application has its own `outbox_event` table (Flyway) and scans the package via a `JpaConfig` (`@EntityScan` / `@EnableJpaRepositories` for both packages). That config is kept **out of the application class** on purpose — on the app class it forced `@WebMvcTest` slices to build repositories and an EntityManager and broke `OrderControllerWebTest`.
- **The `orderRef` is the correlation + idempotency key** across services: generated in `Order.pending(...)` as `UUID.randomUUID()`, stored unique in both `orders.order_ref` and `reservation.order_ref`, and used as the **Kafka message key** so all events of one order stay ordered on one partition. The per-event `eventId` is the consumer's dedupe key.

### Data model

| Table (service) | Important columns / constraints | Why |
|---|---|---|
| `product` (inventory) | `sku unique`, `quantity integer not null check (quantity >= 0)` | DB itself refuses negative stock — last line of defence |
| `reservation` (inventory) | `order_ref unique`, `sku references product(sku)`, `status` RESERVED/RELEASED, `check (quantity > 0)` | unique `order_ref` = idempotency; one reservation per order |
| `payment` (payment) | `event_id uuid PK` (= `OrderConfirmed.eventId`), `order_ref unique`, `amount numeric(12,2)`, `status` CAPTURED/FAILED, `failure_reason` | PK = incoming eventId makes the consumer idempotent without a separate inbox table; one payment per order |
| `processed_event` (order, `V5`) | `event_id uuid PK`, `processed_at` | inbox for `payments.events`; `ProcessedEventCleanup` deletes rows older than 7d daily |
| `orders` (order) | `order_ref unique`, `idempotency_key unique` (nullable), **`customer_id not null`** (JWT `sub`, `V3__order_owner.sql`), `status`, `failure_reason`, `version bigint`, indexes `idx_orders_created_at (created_at desc)`, `idx_orders_customer_created (customer_id, created_at desc)`, `idx_orders_status_updated (status, updated_at)` | duplicate-POST protection; **ownership** (who may read/cancel); optimistic lock; fast "my orders, newest first" listing; reconciler query "FAILED older than X" |
| `outbox_event` (order) | `id uuid pk`, `aggregate_type`, `aggregate_id` (= orderRef = Kafka key), `event_type`, `payload text` (JSON), `created_at`, `published_at` nullable, index `idx_outbox_pending (published_at, created_at)` | transactional outbox: written in the same tx as the order; relay publishes rows where `published_at is null` oldest-first |
| `processed_event` (notification) | `event_id uuid pk`, `order_ref`, `processed_at` | idempotent consumer: duplicate delivery hits the PK and is skipped |

> Note: the table is `orders`, not `order` — `order` is a reserved SQL word (comment in `Order.java`).
> `V3__order_owner.sql` is written as expand → backfill (`'unknown'`) → `NOT NULL` in one file, with a comment that on a live table the `NOT NULL` step would ship in a later release (expand/contract).
> Simplification: one order = one SKU + quantity (no order lines). Be upfront about it.

### API surface

| Service | Method & path | Success | Errors |
|---|---|---|---|
| order | `POST /api/v1/orders` (+ optional `Idempotency-Key`) — role `customer`; the order is stamped with the caller's JWT `sub` | `201 Created` + `Location` (new), `200 OK` (replay **by the same customer**) | 400 validation, 401 no/invalid JWT, 403 wrong role, 409 concurrent same key, **422 "Idempotency-Key conflict"** (key already used by another customer), 429 rate limit (50/s/pod), 503 inventory unavailable (body has `orderId`) |
| order | `GET /api/v1/orders/{id}` — **owner** (`customer`) or `support` | 200 | 401, 403 (wrong role), 404 (unknown id **or someone else's order** — same response, so ids are not enumerable) |
| order | `GET /api/v1/orders?page=0&size=20` — `customer`/`support` | 200, `PagedModel` (`content` + `page` metadata), newest first; a `customer` sees **only their own orders** (`findByCustomerId`), `support` sees all | 400 if `size > 100` |
| order | `DELETE /api/v1/orders/{id}` (cancel) — role `customer`, **owner only** | 200 with `CANCELLED` order | 404 (also for another customer's order), 409 not cancellable, 503 inventory down |
| inventory | `GET /api/v1/products`, `GET /api/v1/products/{sku}` (cached, key = sku) | 200 | 404 |
| inventory | `POST /api/v1/reservations` `{orderRef, sku, quantity}` | 201 (also on idempotent replay) | 400, 404 unknown SKU, 409 insufficient stock / concurrent |
| inventory | `DELETE /api/v1/reservations/{orderRef}` | 204 (always, idempotent) | — |

Note: a REJECTED order still returns **201** — the HTTP request succeeded (an order resource was created); the business outcome is in `status` and `failureReason`. Be ready to defend this (see follow-up Q11).

---

## 3. Request flow — placing an order, step by step

`POST /api/v1/orders` with body `{"sku":"PS5-SLIM","quantity":1}`, header `Idempotency-Key: checkout-42` and `Authorization: Bearer <JWT>`.

0. **Authentication + rate limit** — Spring Security's filter chain runs first: the bearer JWT is validated against the issuer's JWKS (signature, `exp`, `iss`) and `JwtRolesConverter` turns `realm_access.roles` / `cognito:groups` into `ROLE_*`; no/invalid token → **401**, a token without `customer` → **403** (test `rejectsRequestsWithoutAValidToken`). Then the Resilience4j `@RateLimiter("orders")` proxy on `OrderController.create` takes a permit (50 per second per pod, `timeout-duration: 0`); none left → `RequestNotPermitted` → **429** ProblemDetail (test `rateLimitExceededIs429`).
1. **Validation** — `OrderController.CreateOrderRequest` is a record with `@NotBlank @Size(max=64) sku` and `@Min(1) @Max(100) quantity`. Because the `Idempotency-Key` header parameter carries `@Size(max=100)`, Spring 6.1+ method validation applies to the whole method, so an invalid body raises `HandlerMethodValidationException` (not `MethodArgumentNotValidException`) → `GlobalExceptionHandler.handleHandlerMethodValidationException` (overridden from `ResponseEntityExceptionHandler`) adds a sorted `errors` list like `["quantity: must be greater than or equal to 1", "sku: must not be blank"]` → **400 ProblemDetail**. `handleMethodArgumentNotValid` is overridden the same way for plain `@Valid @RequestBody` endpoints (e.g. inventory's reservations).
2. **Idempotency check** — the controller builds a `Caller.from(authentication)` (record: `customerId` = JWT `sub` via `authentication.getName()`, `support` = has `ROLE_support`) and passes it to `OrderService.placeOrder`, which calls `orderRepository.findByIdempotencyKey(key)`. If found **and owned by this caller** → return existing order with `created=false` → controller returns **200** with the same body. Inventory is **not called again** (test `idempotencyKeyPreventsDuplicateOrders` verifies exactly 1 POST to WireMock). If the key exists but belongs to **another customer** → `IdempotencyKeyConflictException` → **422 "Idempotency-Key conflict"** — never replay someone else's order to a different user (test `idempotencyKeyOfAnotherCustomerIsRejected`).
3. **Save PENDING** — `Order.pending(sku, qty, key, caller.customerId())` generates `orderRef = UUID`, status `PENDING`, stamps `customerId`; `orderRepository.save(...)` commits in its own short transaction (Spring Data repository methods are transactional by default). If two requests with the same key race, the second insert hits the `idempotency_key` unique constraint → `DataIntegrityViolationException` → **409 "Concurrent request"**.
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
10. **Payment (asynchronous saga step, choreography)** — payment-service's `OrderConfirmedListener` (`@KafkaListener(topics = "orders.events", groupId = "payment-service")`, `@Transactional`) reads the same record: any `type` other than `OrderConfirmed` is ignored; if `payments.existsById(eventId)` the redelivery is counted (`payments.duplicates`) and skipped; otherwise `PriceList.priceOf(sku) × quantity` → `PaymentGateway.charge(orderRef, amount)` (`FakePaymentGateway` declines above `payment.card-limit: 100000`) → `Payment(eventId, orderRef, amount, CAPTURED|FAILED, reason)` is saved **and** `OutboxPublisher.publish("payments.events", "Payment", orderRef, type, event)` writes a `PaymentCaptured` / `PaymentFailed` row in the **same transaction** → `payments_total{status}` counter. Its own `OutboxRelay` publishes to `payments.events` (key `orderRef`). Back in order-service, `PaymentEventListener` (`groupId = "order-service"`, `@Transactional`) dedupes on `processed_event` (`V5__processed_event.sql`) and calls `OrderService.onPaymentCaptured(orderRef)` → `markPaid()` → **PAID** + `OrderPaid` outbox event, or `onPaymentFailed(orderRef, reason)` → `inventoryClient.release(orderRef)` + `cancel("payment failed: <reason>")` → **CANCELLED**. Both are guarded by `Order::isConfirmed`, so a late or duplicate payment event for an already cancelled/paid order is a no-op. Tests: `paymentCapturedMarksTheOrderPaid`, `paymentFailedReleasesStockAndCancelsTheOrder` (order-service) and the four payment-service tests (capture, decline above limit, redelivery charges once, ignores other events). The client sees this by polling `GET /orders/{id}`: CONFIRMED → PAID typically within ~2s (two relay hops of ≤1s each).

### Cancel flow (saga compensation)

`DELETE /api/v1/orders/{id}` → `OrderService.cancel`:

1. `get(id, caller)`: load the order and filter with `caller.mayAccess(order)` (`support || order.isOwnedBy(customerId)`); missing **or not yours** → `OrderNotFoundException` → **404** (deliberately not 403: a 403 would confirm the id exists — test `customersOnlySeeTheirOwnOrders` also verifies that no `DELETE /reservations` reaches inventory for the foreign cancel).
2. `isCancellable()` is true only for **CONFIRMED** or **FAILED**. Otherwise `OrderNotCancellableException` → 409 (so cancelling twice gives 409, not a second release — test `cancelReleasesReservedStock`). A **PAID** order is not cancellable either: cancelling it would need a refund step (gap, see §6).
3. `inventoryClient.release(orderRef)` → `DELETE /api/v1/reservations/{orderRef}` (also retried + breaker-protected).
4. Inventory `ReservationService.release`: if a reservation exists **and** is RESERVED → mark RELEASED and `incrementStock` (and `@CacheEvict(allEntries = true)` on the `products` cache). Unknown or already released → **no-op, 204**. This is what makes it safe to cancel a FAILED order whose real outcome is unknown.
5. `order.cancel()` → `saveWithEvent` (order + `OrderCancelled` outbox row) → counter → notification-service later logs "Order … was cancelled".

Why **FAILED is cancellable**: FAILED means "we don't know" — the request may have timed out *after* inventory committed. Releasing an unknown `orderRef` is a harmless no-op, so cancel is always safe.

### FAILED and stale PENDING orders — automatic reconciliation (saga timeout)

A customer should not have to cancel a FAILED order by hand. `OrderReconciler` (`@Scheduled(fixedDelayString = "${orders.reconcile.delay:60s}")`) calls `OrderService.reconcileFailedOrders(grace)` with `orders.reconcile.grace: 2m`:

1. `orderRepository.findTop100ByStatusInAndUpdatedAtBefore(List.of(FAILED, PENDING), now - grace)` (index `idx_orders_status_updated`) — orders whose inventory outcome has been unknown for longer than the grace period. A PENDING order this old means the pod died between "save PENDING" and "save outcome" (same limbo), so the sweep covers both.
2. For each: `inventoryClient.release(orderRef)` (idempotent — a no-op if nothing was reserved, frees the stock if the timeout happened after inventory committed) → `order.cancel()` → `saveWithEvent` (`OrderCancelled` outbox event) → `orders_total{status="CANCELLED"}`.
3. If inventory is **still** unavailable (`RestClientException` / `CallNotPermittedException`), the batch stops (`break`) and the orders stay FAILED until the next run — no point hammering a dead dependency.

Result: the customer gets a definite CANCELLED instead of limbo (no more "PENDING orphan"), and leaked reservations are freed within ~3 minutes of inventory coming back. Test `reconcilerCancelsStaleFailedOrdersOnceInventoryIsBack`: with inventory returning 503 the reconciler settles 0; once it returns 204 the order is CANCELLED. Why release instead of re-calling `reserve` to learn the truth: the customer already received a 503, so confirming the order minutes later would surprise them; releasing is the conservative, always-safe choice.

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
| Inventory down / timing out | 503 "Inventory unavailable" with `orderId` | FAILED → CANCELLED | **unknown** — maybe reserved if the timeout happened after commit | user cancels (idempotent release) or `OrderReconciler` releases + cancels after 2m grace |
| Many failures | 503 instantly (breaker OPEN) | FAILED | untouched | breaker half-opens after 10s |
| Read timeout but inventory committed | 503 | FAILED → CANCELLED | **reserved (leaked)** until released | cancel releases it, or `OrderReconciler` does (release is idempotent; stops the batch while inventory is still down) |
| Duplicate POST with same key | 200 with original order | unchanged | unchanged | — |
| Two concurrent POSTs, same key | one 201, other 409 "Concurrent request" | one order | reserved once | client retries → gets 200 |
| order-service crashes after saving PENDING, before reserve result | connection error | PENDING → CANCELLED | maybe reserved | `OrderReconciler` sweeps PENDING older than the 2m grace together with FAILED (`findTop100ByStatusInAndUpdatedAtBefore`): idempotent release + CANCELLED. PENDING is still not cancellable by the client |
| Card declined (amount > card limit) | order CONFIRMED at first, then CANCELLED (`failureReason: payment failed: card limit exceeded ...`) | CONFIRMED → CANCELLED | reserved, then **released** by `onPaymentFailed` | automatic (choreographed compensation); `payments_total{status="FAILED"}` ratio alert `PaymentFailureRateHigh` |
| `PaymentCaptured` delivered twice / order-service restarts mid-listener | nothing | PAID once | — | `processed_event` dedupe + `isConfirmed` guard; the offset is committed only after the DB tx |
| payment-service down | order stays CONFIRMED | CONFIRMED (not PAID) | reserved | consumer lag grows (`KafkaConsumerLagHigh`), events wait in Kafka; on restart they are processed. Gap: no timeout from CONFIRMED |
| Order DB down | readiness DOWN → pod removed from Service; requests 500 | — | — | K8s routes elsewhere / DB recovers |
| Cancel while inventory down | 503 | stays CONFIRMED/FAILED | still reserved | retry cancel later (release is idempotent); a FAILED order is settled by the reconciler anyway |
| Another customer's order id in `GET`/`DELETE` | 404 (not 403) | unchanged | unchanged | — (`customersOnlySeeTheirOwnOrders`) |
| Idempotency-Key reused by a different customer | 422 "Idempotency-Key conflict" | unchanged | unchanged | client picks its own key (`idempotencyKeyOfAnotherCustomerIsRejected`) |
| Redis down (inventory) | product reads slower, still 200 | — | — | `LoggingCacheErrorHandler`: cache errors logged, reads fall through to Postgres (fail-open) |
| Pod receives SIGTERM | in-flight requests finish (`server.shutdown: graceful`, 20s phase timeout) | — | — | new requests go to other pods |

> Tip: Interviewers love it when you list your own gaps ("PENDING replay returns a stale order", "no refund path for PAID", "no timeout from CONFIRMED if payment-service is down") and what you already closed (FAILED + PENDING reconciliation, ownership check, audience validation). Yeh maturity dikhata hai.

### 4.9 Other small but explainable decisions

- **`ddl-auto: validate` + Flyway** — schema is versioned SQL in Git (`V1__...sql`), reviewed like code, applied the same way in every environment; Hibernate only checks entities match. `update` in prod can silently do partial or destructive changes.
- **`open-in-view: false`** — no DB connection held during JSON serialisation / view rendering; lazy-loading surprises surface as errors in dev instead of hidden N+1 queries in prod. DTO records (`OrderResponse`, `ProductResponse`, `ReservationResponse`) are built inside the service/tx boundary.
- **Records as DTOs** — immutable, no boilerplate; entities are never exposed directly.
- **`@ConfigurationProperties` record** — `InventoryProperties(baseUrl, connectTimeout, readTimeout)` with `Duration` binding (`1s`, `2s`), enabled by `@ConfigurationPropertiesScan` on `OrderServiceApplication`. Env override: `INVENTORY_URL`.
- **Externalised config** — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `INVENTORY_URL` with local defaults (`${DB_URL:jdbc:postgresql://localhost:5432/orders}`) → same jar in every environment (12-factor).
- **Graceful shutdown** — `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`; pairs with the Deployment's `preStop: sleep 5` and `terminationGracePeriodSeconds: 45` (must exceed preStop + shutdown phase: 5 + 20 < 45).
- **Metrics** — `management.metrics.tags.application` tags every metric with the service name; `percentiles-histogram.http.server.requests: true` publishes histogram buckets so Prometheus can compute p95/p99 with `histogram_quantile`.
- **Business metrics** — `orders_total{status=...}` (order-service), `inventory_reservations_rejected_total` (inventory-service), `outbox_unpublished` gauge (order-service), `notifications_sent_total{type}` / `notifications_duplicates_total` (notification-service). Alerts (`monitoring/alert-rules.yml`): 5xx ratio > 5% (HighErrorRate), p99 > 1s excluding `/actuator` (HighP99Latency), `rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="inventory"}[5m]) > 0` (InventoryCircuitOpen), `up == 0` (ServiceDown), `outbox_unpublished > 100` for 5m (OutboxBacklogGrowing — Kafka down or relay stuck), consumer lag > 1000 for 10m (KafkaConsumerLagHigh). A business alert to add: spike in `orders_total{status="FAILED"}`.
- **Outbox housekeeping** — `OutboxRelay.cleanup()` (`@Scheduled(cron = "${outbox.cleanup.cron:0 30 3 * * *}")`, 03:30 UTC daily) runs `OutboxRepository.deletePublishedBefore(now - outbox.cleanup.retention)` (`7d`): a `@Modifying` JPQL delete of rows with `published_at < :before`; unpublished rows are never touched. Published rows are only kept for debugging, so without this the table would grow forever. Test `OutboxCleanupTest.deletesOnlyPublishedEventsOlderThanRetention`.
- **Cache fail-open** — inventory's `CacheConfig implements CachingConfigurer` and returns a `LoggingCacheErrorHandler`: a Redis `get`/`put`/`evict` error is logged and the `@Cacheable` method body runs against the DB. A cache must never be a hard dependency of a read path (readiness already excluded Redis by default; now the request path tolerates it too).
- **MSK profile** — `application-aws.yml` (order- and notification-service) sets `security.protocol: SASL_SSL`, `sasl.mechanism: AWS_MSK_IAM`, `sasl.jaas.config: ...IAMLoginModule required;`, `sasl.client.callback.handler.class: ...IAMClientCallbackHandler`; runtime dependency `software.amazon.msk:aws-msk-iam-auth:2.3.9` (version in the parent pom); the prod overlay sets `SPRING_PROFILES_ACTIVE=aws` on both Deployments. Credentials come from the IRSA role via the AWS default chain — no username/password anywhere.
- **Tracing** — parent pom brings `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`; `management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}`, `management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:...4318/v1/traces}`; `spring.kafka.template/listener.observation-enabled: true` carries the trace through Kafka. Disabled in tests (`management.otlp.tracing.export.enabled: false`).
- **Virtual threads** — `spring.threads.virtual.enabled: true` in all four services: blocking JDBC/HTTP/Kafka waits no longer pin a platform thread; the DB pool becomes the real concurrency limit (hence explicit `DB_POOL_SIZE`).
- **Validation error details** — both `GlobalExceptionHandler`s override `handleMethodArgumentNotValid` and `handleHandlerMethodValidationException` to add a sorted `errors` list (`"field: message"`) to the ProblemDetail; asserted in `OrderServiceApplicationTests.validatesRequests`.
- **Pagination with limits** — `size` capped at 100 via `@Max(100)` on a `@RequestParam` → 400 for `size=1000` (test `validatesRequests`). Sorted by `createdAt DESC`, backed by index `idx_orders_created_at`. Returns `PagedModel` (stable JSON shape: `content` + `page`).
- **ProblemDetail everywhere** — consistent error JSON; `InventoryUnavailableException` adds an extension property `orderId` so the client can cancel or poll it.
- **`ProductController` has `@Transactional(readOnly = true)` at class level** — read-only hint to Hibernate (no dirty checking / flush). Honest note: usually you put this on a service, not a controller.

---

## 5. Testing story

| Test class | Style | What it proves |
|---|---|---|
| `inventory-service/src/test/.../InventoryServiceApplicationTests.java` (10 tests) + `config/CacheSerializationTest.java` (1) | `@SpringBootTest` + `@AutoConfigureMockMvc` + `@AutoConfigureObservability` + profile `test` (H2 `MODE=PostgreSQL`, cache type `simple`) | idempotent reserve, release-only-once, 409/404/400 mapping, **20-thread no-oversell test**, 8 concurrent retries reserve once, **product read cached and evicted on reserve** (`productReadIsCachedAndEvictedOnReserve` via `CacheManager`), probes + `/actuator/prometheus`; `CacheSerializationTest` pins the JSON round-trip of `ProductResponse` through `GenericJackson2JsonRedisSerializer` |
| `order-service/src/test/.../OrderServiceApplicationTests.java` (17 tests) | `@SpringBootTest` + MockMvc + **WireMockServer on a dynamic port** wired via `@DynamicPropertySource` (`inventory.base-url`) + **`@EmbeddedKafka(partitions = 1, topics = "orders.events")`**; every request carries a `jwt()` with `ROLE_customer` and a subject (`customer()` = alice, `customer("mallory")`, `support()`) | CONFIRMED path, REJECTED without retry, retry after 503 (WireMock *scenario*), 503 + FAILED after 3 attempts, **breaker opens and makes 0 calls**, Idempotency-Key replay, cancel → DELETE call, validation, pagination, **401 / 403 / open liveness**, **ownership: another customer gets 404 on GET/DELETE and no release reaches inventory, list is scoped, support sees all** (`customersOnlySeeTheirOwnOrders`), **foreign Idempotency-Key → 422** (`idempotencyKeyOfAnotherCustomerIsRejected`), **reconciler: 0 settled while inventory is 503, CANCELLED once it is back** (`reconcilerCancelsStaleFailedOrdersOnceInventoryIsBack`), **outbox → Kafka record keyed by orderRef** (`publishesOrderEventThroughTheOutbox`), **payment saga: `PaymentCaptured` → PAID, `PaymentFailed` → release + CANCELLED** (`paymentCapturedMarksTheOrderPaid`, `paymentFailedReleasesStockAndCancelsTheOrder`), readiness independent of inventory |
| `order-service/src/test/.../architecture/ArchitectureTest.java` (7 rules) | **ArchUnit** `@AnalyzeClasses(packages = "com.shopflow.order", DoNotIncludeTests)` + `@ArchTest` | controllers never depend on `*Repository` or `@Entity` classes; `*Service` classes never depend on `org.springframework.web.bind..` / `jakarta.servlet..`; `@RestController`s are named `*Controller`; no field injection; no `System.out`; no `java.util.logging` |
| `order-service/src/test/.../security/AudienceValidatorTest.java` (2 tests) | plain JUnit on `JwtDecoderConfig.audienceValidator` | accepts Keycloak (`aud`/`azp`) and Cognito (`client_id`) shapes; rejects a token minted for another API |
| `payment-service/src/test/.../PaymentServiceApplicationTests.java` (4 tests) | `@SpringBootTest` + `@EmbeddedKafka` + a raw consumer subscribed to `payments.events` | `OrderConfirmed` → `payment` row CAPTURED + `PaymentCaptured` published; amount above `payment.card-limit` → FAILED + `PaymentFailed`; the same event delivered twice charges **once** (`payments.duplicates`); `OrderCancelled` etc. are ignored |
| `order-service/src/test/.../OutboxCleanupTest.java` (1 test) | `@SpringBootTest` + `@EmbeddedKafka`, calls `OutboxRelay.cleanupPublishedBefore` directly | a published row older than the cut-off is deleted, an unpublished row is kept (`deletesOnlyPublishedEventsOlderThanRetention`) |
| `order-service/src/test/.../OrderControllerWebTest.java` (2 tests) | `@WebMvcTest(OrderController.class)` + `@Import(SecurityConfig, JwtRolesConverter)` + `@MockitoBean JwtDecoder` + `@MockitoBean OrderService` | error mappings hard to provoke end-to-end: optimistic-lock → **409**, `RequestNotPermitted` → **429** |
| `notification-service/src/test/.../NotificationServiceApplicationTests.java` (2 tests) | `@SpringBootTest` + `@EmbeddedKafka(topics = {"orders.events", "orders.events.DLT"})` + Awaitility | same event delivered twice → processed once (`notifications.duplicates` ≥ 1, `notifications.sent` = 1); **poison pill ("this is not json") goes to the DLT and the next event is still processed** |

Test-profile tweaks (`application-test.yml`): H2 URL, `spring.kafka.bootstrap-servers: ${spring.embedded.kafka.brokers}`, a fake `issuer-uri` (never contacted — `jwt()` injects the authentication), `inventory.read-timeout: 500ms`, retry `wait-duration: 10ms`, `outbox.relay.delay: 100ms`, tracing export off → fast tests. **46 tests in total** (inventory 11, order 29, notification 2, payment 4).

Honest limitation: H2 is "Postgres-like", not Postgres. Locking semantics and SQL dialect can differ. Next step: **Testcontainers** with a real `postgres:16` image (and `@ServiceConnection`).

---

## 5b. Deployment & operations (the DevOps half of the story)

| Concern | Where | What to say |
|---|---|---|
| Image | `shopflow/Dockerfile` | 3 stages: Maven build (BuildKit cache mount for `~/.m2`, `-pl ${SERVICE} -am`) → `java -Djarmode=tools ... extract --layers` → `eclipse-temurin:21-jre-alpine` runtime, non-root UID 10001, layers copied dependencies-first so a code change ships a tiny layer, `JarLauncher` entrypoint, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |
| Local stack | `docker-compose.yml` | Postgres 16 with `init-db.sh` creating the four DBs (`orders`, `inventory`, `notifications`, `payments`), Kafka `apache/kafka:4.1.0` in KRaft mode (3 default partitions), Redis 7.4 (`allkeys-lru`, 64mb), Keycloak 26.3 with the `shopflow` realm imported; services wait for postgres **and kafka** `service_healthy`, healthchecks on `/actuator/health/readiness`, 512m memory limits, ECS JSON logs; Prometheus + Grafana + Tempo + Loki + Alloy |
| Probes | `k8s/base/*/deployment.yaml` | startupProbe on liveness (2s × 30 = 60s for JVM start), liveness every 10s, readiness every 5s |
| Resources | same | requests `cpu: 250m`, `memory: 384Mi`; limit `memory: 512Mi`; **no CPU limit** on purpose (throttling slows JVM startup and GC) |
| Availability | `hpa.yaml`, `pdb.yaml`, `topologySpreadConstraints` | HPA CPU 70%, PDB `minAvailable: 1` for node drains, replicas spread across nodes (notification-service too) |
| Network | `network-policies.yaml`, `ingress.yaml` | default deny ingress **and egress** (DNS allowed); explicit allow-list per service: inventory only from order-service, Kafka only from order/notification/payment, Redis only from inventory, Keycloak from ingress + order-service (JWKS), notification and payment only from monitoring (`payment-service-ingress` 8084, `payment-service-egress` → postgres + kafka + monitoring); egress to Tempo (4318) in `monitoring`; Ingress exposes only public paths |
| Config/secrets | env vars in Deployments, `shopflow-endpoints` ConfigMap, `shopflow-db` + `keycloak-admin` Secrets | endpoints ConfigMap holds `kafka-bootstrap-servers`, `redis-host`, `jwt-issuer-uri`, `otlp-endpoint` (dev: in-cluster names; prod: `behavior: replace` with MSK / ElastiCache / Cognito / collector + `rds-endpoint`); dev secrets via `secretGenerator`; prod synced from AWS Secrets Manager by External Secrets Operator |
| Environments | `k8s/overlays/dev`, `k8s/overlays/prod` | dev: 1 replica, `:dev` images, `imagePullPolicy: IfNotPresent`; prod: 3–10 replicas, GHCR images pinned to the git SHA by CI, TLS host `shop.example.com`, **`$patch: delete` removes the in-cluster Postgres/Kafka/Redis/Keycloak and their NetworkPolicies**, adds egress to the VPC CIDR (5432/9092/9098) and 443 for Cognito JWKS |
| Database / broker / cache / IdP | `k8s/base/{postgres,kafka}/statefulset.yaml`, `redis/`, `keycloak/` | demo single-replica StatefulSets (Postgres with four logical DBs, Kafka KRaft combined node with a 2Gi PVC), Redis Deployment with no volume ("losing it only costs cache misses"), Keycloak Deployment with realm from a ConfigMap; prod = RDS / MSK / ElastiCache / Cognito from `infra/terraform/aws` |

> Tip: Interviewer DevOps side pooche toh "image → compose → k8s → CI → alerts" order me bolo. Har step ka ek reason ready rakho (non-root kyun, CPU limit kyun nahi, preStop kyun).

---

## 6. What would you improve next? (have 3-4 ready)

| Improvement | Why | How (concretely) |
|---|---|---|
| **Event-driven reservation** | Today order→inventory is still synchronous; an inventory outage = failed orders. The outbox + Kafka already exist for notifications; extending them to the reservation would decouple availability. | Emit `OrderPlaced` from the PENDING save; inventory consumes, reserves idempotently (it already dedupes on `orderRef`), publishes `StockReserved`/`StockRejected`; order consumes and updates status. Cost: the client must poll a PENDING order. |
| **Refund flow for cancelling a PAID order** | `isCancellable()` covers CONFIRMED/FAILED only; once PAID, the money has moved, so cancel needs a compensating `refund` on the gateway and a `PaymentRefunded` event | `OrderService.cancel` on PAID → publish `RefundRequested`; payment-service refunds (idempotent by `orderRef`) → `PaymentRefunded` → order releases stock + CANCELLED (same choreography as the failure path). |
| **Timeout from CONFIRMED** | if payment-service is down, orders sit in CONFIRMED with stock reserved; the reconciler only sweeps FAILED/PENDING | extend the reconciler: CONFIRMED older than N minutes without a payment event → release + CANCELLED (or re-emit `OrderConfirmed`). |
| **Service-to-service auth** | `aud` is now validated (`JwtDecoderConfig`, `JWT_AUDIENCE`), but inventory/notification/payment still have no app-level auth | client-credentials tokens or a service mesh (mTLS) between services; optionally move the ownership rule to `@PostAuthorize` (`@EnableMethodSecurity` is already on). |
| **Distributed rate limiting at the edge** | today only an NGINX Ingress does path routing; the Resilience4j limit is **per pod** (50 rps × N pods) and not per user | NGINX Ingress annotations (`limit-rps`) or Spring Cloud Gateway / Kong with a Redis token bucket per user; add `Retry-After`. |
| **Redis cache test against a real Redis** | the JDK-serialization trap is already closed: `inventory-service/config/CacheConfig.java` (`RedisCacheManagerBuilderCustomizer` + `GenericJackson2JsonRedisSerializer`) stores values as JSON and `CacheSerializationTest` pins the round-trip — but no test exercises `@Cacheable` against a real Redis | Testcontainers Redis profile running `productReadIsCachedAndEvictedOnReserve`. |
| **Schema Registry / contract tests** | the event contract is a copied record with `ignoreUnknown`; a rename would silently break the consumer | Avro/JSON Schema + registry with BACKWARD compatibility in CI, or a shared contract test. |
| **Testcontainers** | real Postgres/Kafka/Redis semantics in tests | `@Testcontainers` + `PostgreSQLContainer` + `@ServiceConnection`. |
| **Idempotency hardening** | same key + different body is still replayed (only a different *customer* gets 422 today); keys never expire | store request hash, 422 on mismatch; TTL cleanup. |
| **Jitter on retries, bulkhead** | avoid thundering herd; isolate inventory call threads | `randomized-wait-factor`; Resilience4j `@Bulkhead`. |
| **Outbox → CDC** | the nightly `cleanup` keeps the table bounded (and `ProcessedEventCleanup` does the same for the inbox tables), but polling still adds ~1s latency and one query per second per pod per service | Debezium CDC on `outbox_event` when volume or latency requirements grow; partition the table by day if the daily delete gets heavy. |
| **Exemplars + alert on DLT** | traces and metrics are linked only via logs today; nobody is alerted on `orders.events.DLT` | enable exemplar storage in Prometheus; alert on DLT message count / consumer-group lag on the DLT. |
| **Multi-line orders** | realistic carts | `order_line` table, reserve multiple SKUs in one inventory tx (sort SKUs to avoid deadlocks). |

---

## 7. Likely follow-up questions (crisp answers)

**Q1. Why four services and not a monolith?**
To practise real distributed-system problems: network failures, partial failure, idempotency, and data ownership. Inventory, orders and payments also have different scaling, change and compliance patterns (payment code is the part you would isolate and audit). For a real 3-person startup I'd honestly start with a modular monolith and split when needed.

**Q2. How do you guarantee no overselling?**
A single conditional `UPDATE product SET quantity = quantity - :qty WHERE sku = :sku AND quantity >= :qty`. The row lock serialises concurrent updates and the WHERE is re-checked on the latest committed row. If 0 rows updated → 409. The `CHECK (quantity >= 0)` constraint is a backstop. Proven by a 20-thread test.

**Q3. What if the same reservation request arrives twice at the same time?**
Both may miss `findByOrderRef`, both decrement, but only one insert passes the `order_ref` unique constraint. The loser's transaction rolls back **including its decrement** (same `@Transactional`). `ReservationController` catches that `DataIntegrityViolationException` *outside* the transaction and returns the **winner's reservation with 201**, so both callers see the same answer and stock moves once (`concurrentRetriesOfTheSameOrderReserveOnce`: 8 parallel requests, 8 × 201, stock −1). Why in the controller and not inside `reserve()`? Catching inside the `@Transactional` method would leave the transaction marked rollback-only and still fail at commit. Earlier version returned 409 here, which order-service would have mis-read as "insufficient stock" → REJECTED although stock was reserved; a good example of why a 409 must mean exactly one thing per endpoint.

**Q4. Inventory timed out but actually reserved the stock. Now what?**
Order is FAILED and the client gets 503 with the `orderId`. The reservation may exist. Two ways out, both built on the idempotent `release`: the customer cancels the FAILED order right away, or `OrderReconciler` does it automatically — every 60s it picks up to 100 FAILED orders older than the 2-minute grace, calls `release(orderRef)` (no-op if nothing was reserved) and marks them CANCELLED with an `OrderCancelled` event. The alternative, re-calling `reserve` to learn the truth and confirm, I rejected on purpose: the customer already saw a 503, so a surprise CONFIRMED minutes later is worse than a clean CANCELLED.

**Q5. Why not put `@Transactional` on `placeOrder`?**
A remote call inside a transaction holds a DB connection for up to ~6.6s; with a pool of 10, ten slow orders block the whole service. Also the remote reservation can't be rolled back by our DB rollback, so the transaction gives false safety. Instead every state change is a short separate save and the status tells us where we stopped.

**Q6. Why is Retry outside the CircuitBreaker?**
Resilience4j's default aspect order is `Retry(CircuitBreaker(...))`. Every attempt is recorded by the breaker, so it opens quickly when the downstream is dead, and once open, the `CallNotPermittedException` is not in `retry-exceptions`, so we fail fast instead of retrying an open circuit.

**Q7. Why don't 409s open the circuit breaker?**
They're healthy responses from a healthy service. `InventoryRejectedException` is in `ignore-exceptions`. Otherwise a flash sale where stock runs out would "open" the circuit and block orders for other in-stock products.

**Q8. Which HTTP errors are retried?**
Only `ResourceAccessException` (connection refused, timeouts) and `HttpServerErrorException` (5xx). Never 4xx. And retrying POST is only safe because the reservation is idempotent by `orderRef`.

**Q9. What is the `Idempotency-Key` flow, and what happens if the client sends none?**
With key: lookup → replay returns 200 with the original order, but only for the customer who created it — the same key from a different JWT `sub` gets 422 "Idempotency-Key conflict". Without key: every POST creates a new order (the header is `required = false`). In production I'd make it mandatory for checkout clients or have the gateway generate one.

**Q10. How does optimistic locking help in order-service?**
`@Version` on `Order`. Hibernate adds `WHERE version = ?` to updates; if another transaction updated the row first, 0 rows → `ObjectOptimisticLockingFailureException`. Prevents lost updates like a cancel racing with a status update.

**Q11. Why return 201 for a REJECTED order instead of 409?**
The order resource *was* created and persisted with an id, and the client can GET it later. The business outcome is in the body. Alternative design: return 409/422 and not persist rejected orders. Both are defensible — what matters is consistency and documentation (OpenAPI).

**Q12. How would you scale this?**
Services are stateless → horizontal pod scaling. Already configured: HPA on CPU at 70% of the request (`cpu: 250m`), 2–6 pods in base, 3–10 in the prod overlay, 5-minute scale-down stabilisation. DB: pool size × pods must stay under Postgres `max_connections`: the Deployments set `DB_POOL_SIZE=5`, so 2 services × 10 pods (HPA max) × 5 = 100 connections, and the StatefulSet runs Postgres with `max_connections=200` (at real scale: PgBouncer). Hot SKUs: the row lock on one product becomes the bottleneck — options are stock sharding (split stock across N rows), queue-based reservation, or Redis atomic `DECRBY` with DB reconciliation.

**Q13. How do you monitor it in production?**
Prometheus scrapes `/actuator/prometheus` on all four services. Dashboards: RED metrics from `http_server_requests_seconds` (rate, errors, p95 via histogram buckets), `orders_total` by status, `inventory_reservations_rejected_total`, `resilience4j_circuitbreaker_state`, `outbox_unpublished`, Kafka consumer lag, `notifications_sent_total`, cache hit ratio, Hikari pool metrics (`hikaricp_connections_active/pending`), JVM memory/GC. Alerts in `monitoring/alert-rules.yml`: HighErrorRate (5xx > 5% for 5m, page), HighP99Latency (> 1s for 10m, ticket), InventoryCircuitOpen (page), ServiceDown, OutboxBacklogGrowing (page), KafkaConsumerLagHigh (ticket). Traces go over OTLP to Tempo (sampling `TRACING_SAMPLE`), logs are ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) shipped by Alloy to Loki, and Grafana links a span to its log lines and a `trace.id` in a log line back to the trace.

**Q14. What does graceful shutdown actually do?**
On SIGTERM, Tomcat stops accepting new connections, waits up to 20s for in-flight requests, then the context closes (pools closed). In K8s, readiness goes to refusing traffic too. The Deployments have `preStop: sleep 5` so the pod is removed from Service/Ingress endpoints *before* SIGTERM, and `terminationGracePeriodSeconds: 45` so K8s doesn't SIGKILL during the 20s drain. Rolling updates use `maxUnavailable: 0`, `maxSurge: 1`.

**Q15. Why Flyway + `ddl-auto=validate`?**
Schema changes are versioned, reviewable and repeatable; the app fails fast at startup if entities and schema disagree. Rule: never edit an applied migration — add `V3__...`. For zero-downtime: expand/contract (add nullable column → deploy → backfill → make NOT NULL later).

**Q16. How did you test the resilience logic without a real inventory service?**
WireMock on a random port, injected via `@DynamicPropertySource`. I stub 409 (expect 1 call, REJECTED), a scenario 503→201 (expect 2 calls, CONFIRMED), constant 500 (expect 3 calls, FAILED + 503), and assert the breaker is OPEN and the next call makes 0 HTTP requests. The breaker is reset in `@BeforeEach` so tests are independent.

**Q17. Why H2 in tests — isn't that risky?**
It's fast and needs no Docker, and `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` covers the dialect basics; Flyway runs the same migrations. Risk: locking/isolation differences. Next step is Testcontainers with real Postgres, at least for the concurrency test.

**Q18. Is there a security layer?**
Yes, in order-service: Spring Security as an **OAuth2 resource server** (`spring-boot-starter-oauth2-resource-server`). Requests carry a JWT issued by Keycloak (compose/minikube, realm `shopflow`, users `alice`=customer, `bob`=support) or Amazon Cognito (prod); Spring validates signature via the issuer's JWKS, `exp` and `iss`; `JwtRolesConverter` maps `realm_access.roles` or `cognito:groups` to `ROLE_*`. Rules: GET orders → `customer` or `support`, writes → `customer`, actuator/swagger open (network-restricted), everything else denied; stateless, CSRF off (bearer tokens, no cookies). On top of the role rules, **object-level authorization**: orders carry the creator's `sub`, a customer only sees/cancels their own (others → 404), support reads everything (Q23/Q24). Tests inject fake JWTs with `jwt().authorities(...)`. Infrastructure security on top: NetworkPolicies (default deny ingress + egress; inventory only from order-service; Kafka only from order/notification; Redis only from inventory), non-root UID 10001, `readOnlyRootFilesystem`, all capabilities dropped, `automountServiceAccountToken: false`, Secrets (External Secrets in prod), Trivy + Dependabot. Honest gaps: no ownership check (any customer can read any order), `aud` not validated, inventory/notification rely on network isolation only. Details: [11-security-caching-performance.md](11-security-caching-performance.md).

**Q19. Is there service discovery / a gateway / Kafka / tracing / caching?**
Kafka: yes — transactional outbox (shared `shopflow-outbox` module) in order- and payment-service → `orders.events` → notification-service + payment-service, `payments.events` → order-service (idempotent consumers, DLTs). Tracing: yes — Micrometer Tracing + OTel bridge, OTLP to Tempo, trace ids in JSON logs in Loki, propagated over HTTP and Kafka headers. Caching: yes — Spring Cache on `GET /products/{sku}` with Redis in compose/K8s, evicted on reserve/release. A real API gateway is **not** in the project: discovery is plain Kubernetes DNS (`INVENTORY_URL=http://inventory-service:8082`, no Eureka) and the edge is an NGINX Ingress (`shopflow.local`, TLS via cert-manager in prod) routing only `/api/v1/orders` and `/api/v1/products` — `/api/v1/reservations` is deliberately not exposed publicly. Rate limiting exists but per pod inside order-service, not at the edge.

**Q20. What was the hardest bug / most interesting part?**
Good answer: the `clearAutomatically`/`flushAutomatically` detail in `release()` — the bulk `incrementStock` update bypasses the persistence context, so the `RELEASED` status change on the managed `Reservation` must be flushed before the context is cleared, otherwise the status change is lost and a second release would add stock twice. The `releaseRestoresStockOnlyOnce` test guards it.

**Q21. What's the response time impact of all this resilience?**
Happy path: none (one HTTP call). Transient failure: +200ms/+400ms backoff. Dead downstream: first few requests up to ~6.6s, then the breaker makes failures instant. That's the trade-off you tune with timeouts and attempts.

**Q22. How would you deploy it?**
CI (`.github/workflows/shopflow.yml`): `./mvnw -B verify` → `kustomize build | kubeconform -strict` for every overlay + `promtool check rules` + `docker compose config` → `terraform fmt -check` + `validate` on `infra/terraform/aws` → build each image from the shared `Dockerfile` (matrix over the four services, GHA layer cache) → Trivy scan failing on fixable HIGH/CRITICAL → CycloneDX SBOM (`anchore/sbom-action`, uploaded as `sbom-<service>.cdx.json`) → push to GHCR tagged with the commit SHA (and `latest`) on `main` only → `cosign sign --yes <image>@<digest>` keyless with the job's OIDC token → `deploy-manifests` job runs `kustomize edit set image` for all four services in `k8s/overlays/prod` and commits the SHA back (GitOps). Argo CD (`shopflow/argocd/application-prod.yaml`: automated sync, prune, selfHeal, `ignoreDifferences` on replicas) applies the prod overlay; dev is synced by hand from `application-dev.yaml`. The same stages exist as a declarative `shopflow/Jenkinsfile`. Kubernetes: Deployments with startup/liveness/readiness probes on the actuator endpoints, Secret `shopflow-db` for passwords (secretGenerator in dev, External Secrets in prod), endpoints ConfigMap for Kafka/Redis/issuer/OTLP, HPA, PDB `minAvailable: 1`, topology spread across nodes, Prometheus scrape annotations.

---

**Q23. Can one customer read another customer's order? (IDOR / OWASP API #1)**
No. Every order stores the creator's JWT `sub` (`Order.customerId`, migration `V3__order_owner.sql`). The controller turns the `Authentication` into a `Caller(customerId, support)` and the service filters: `get` returns the order only if `caller.mayAccess(order)` (owner or `ROLE_support`), `list` uses `findByCustomerId` unless the caller is support, and `cancel` goes through `get`. Role checks in `SecurityConfig` answer "may this *kind* of user call this endpoint"; the ownership check answers "may *this* user touch *this* row" — both are needed. Test `customersOnlySeeTheirOwnOrders`.

**Q24. Why 404 and not 403 for someone else's order?**
A 403 would tell an attacker that the id exists ("there is an order 42, just not yours"), which turns sequential ids into an enumeration oracle. Returning the same 404 as for a missing id leaks nothing. The same reasoning applies to the Idempotency-Key: a key owned by another customer gets a 422 "Idempotency-Key conflict" rather than replaying their order to you.

**Q25. Is the reconciler safe? Could it release stock for an order that was actually fine?**
It only touches FAILED and long-PENDING orders, and both mean the reserve outcome is unknown — the saga never reached CONFIRMED. `release` on inventory is idempotent and tolerant: unknown `orderRef` or already-released → no-op, RESERVED → stock back. So the worst case is a harmless no-op, never a double release (the `RELEASED` status flip is tested by `releaseRestoresStockOnlyOnce`). It also stops the batch when inventory throws, so an outage is not amplified by a background loop, and it runs through the same `@Retry`/`@CircuitBreaker` client as the request path.

**Q26. Why choreography for the payment step when reserve is orchestrated?**
Reserve needs an answer *inside the HTTP request* ("is it in stock?"), so order-service calls inventory synchronously and decides. Payment does not: the customer already has a CONFIRMED order and can poll. Reacting to `OrderConfirmed` means order-service has no payment client, no payment timeouts and no breaker; payment-service can be down for an hour and the events simply wait in Kafka (lag alert, not failed orders). The `OrderConfirmed` event already existed for notifications, so adding a consumer group cost nothing on the producer side. Cost: the flow is spread over three services and only visible in tracing (`traceparent` in Kafka headers) — that is why I kept the state machine explicit in `OrderStatus` and both listeners tiny.

**Q27. How is a payment failure compensated?**
`FakePaymentGateway` declines → payment-service saves `Payment(FAILED, reason)` and, in the same transaction, an outbox `PaymentFailed` row (so a crash cannot lose the decline). The relay publishes it on `payments.events`; order-service's `PaymentEventListener` dedupes on `processed_event`, then `OrderService.onPaymentFailed` calls `inventoryClient.release(orderRef)` (idempotent, retried + breaker-protected) and `order.cancel("payment failed: <reason>")` → CANCELLED + `OrderCancelled` event, so notification-service tells the customer. If `release` throws, the listener transaction rolls back, the offset is not committed and the record is retried with backoff (then DLT) — compensation is retryable until it succeeds. Test `paymentFailedReleasesStockAndCancelsTheOrder` asserts the `DELETE /reservations/{orderRef}` reaches WireMock and the order is CANCELLED.

**Q28. How does payment-service achieve the exactly-once effect without Kafka transactions?**
At-least-once delivery + idempotent processing. The `payment` table's **primary key is the `eventId` of the `OrderConfirmed` event**, so a redelivered record (relay crash after `send()` before `markPublished()`, consumer crash before the offset commit, rebalance) hits `existsById` and is skipped; if two pods race, the second insert violates the PK and the record is retried, finds the row, skips. The charge, the `payment` row and the outgoing `PaymentCaptured`/`PaymentFailed` outbox row are **one local transaction**, so you can never have a charge without its event or an event without its charge. Test `redeliveredEventChargesOnlyOnce`. Honest limit: the *gateway* call itself is outside the DB transaction — with a real processor you pass `orderRef` as the gateway's idempotency key so a crash between charge and commit charges once (Stripe/Razorpay support this).

**Q29. Why ArchUnit tests, not just code review?**
Review catches a rule violation once; the test catches it every build, including from contributors who never read the ADR. `ArchitectureTest` encodes the rules we actually rely on: controllers do not touch repositories or `@Entity` classes (DTOs only — no schema leaks, no lazy-loading surprises), `*Service` classes do not depend on Spring Web/servlet (they must stay callable from Kafka listeners and schedulers — `OrderService.onPaymentCaptured` is called from a `@KafkaListener`), naming, constructor injection, no `System.out`, no `java.util.logging`. It runs in `./mvnw verify` with no infrastructure and the failure message names the offending class and line.

---

## 8. One-page cheat sheet

```
PITCH     4 services + shared outbox module, DB-per-service, sync REST for reserve (orchestration) + Kafka for payment/notify (choreography), saga states, idempotent everything.
OVERSELL  UPDATE ... WHERE quantity >= :qty   (+ CHECK quantity >= 0, + 20-thread test)
IDEMP     Idempotency-Key -> orders.idempotency_key UNIQUE ; orderRef -> reservation.order_ref UNIQUE ; eventId -> processed_event PK (notification, order) | payment.event_id PK (payment)
RETRY     3 attempts, 200ms x2 backoff, only ResourceAccessException + 5xx, no jitter (yet)
BREAKER   count window 10, min 5 calls, 50% -> OPEN 10s -> HALF_OPEN 2 calls; ignores business 409/404
TIMEOUTS  connect 1s, read 2s  -> worst case ~6.6s before breaker opens
STATES    PENDING -> CONFIRMED | REJECTED | FAILED ; CONFIRMED -> PAID (PaymentCaptured) | CANCELLED (PaymentFailed: release) ; CONFIRMED|FAILED -> CANCELLED (client cancel) ; FAILED|PENDING > 2m -> OrderReconciler (60s) release + CANCELLED
NO TX     no @Transactional around HTTP (pool of 10 would starve); saveWithEvent = short TransactionTemplate (order + outbox row)
OUTBOX    shopflow-outbox module: outbox_event(topic col) -> OutboxRelay @Scheduled 1s, FOR UPDATE SKIP LOCKED, batch 100, acks=all, key=orderRef -> event.getTopic() ; cleanup 03:30 UTC deletes published > 7d
PAYMENT   OrderConfirmedListener (group payment-service, OrderConfirmed only) -> PriceList x qty -> gateway (decline > 100000) -> payment row + PaymentCaptured|PaymentFailed outbox in ONE tx -> payments.events -> PaymentEventListener (group order-service) -> PAID | release+CANCELLED
CONSUMER  groups notification-service + payment-service (orders.events), order-service (payments.events); auto-commit off, dedupe, ExponentialBackOff 0.5s x2 <=10s -> <topic>.DLT ; ProcessedEventCleanup daily 7d
AUTH      resource server, JWT RS256 via JWKS (SupplierJwtDecoder, lazy), aud|azp|client_id == JWT_AUDIENCE when set, stateless, CSRF off; GET customer|support, writes customer; Keycloak local / Cognito prod
OWNER     Order.customerId = sub; Caller(customerId, support); not yours -> 404 (not 403); list scoped; foreign Idempotency-Key -> 422
CACHE     @Cacheable("products") DTO by sku, @CacheEvict on reserve/release, simple|redis (TTL 60s, allkeys-lru); LoggingCacheErrorHandler = fail-open
MSK       application-aws.yml: SASL_SSL + AWS_MSK_IAM (aws-msk-iam-auth 2.3.9, IRSA creds); prod overlay SPRING_PROFILES_ACTIVE=aws
LIMIT     @RateLimiter("orders") 50/s/pod, timeout 0 -> 429 ProblemDetail
TRACE     micrometer-tracing-bridge-otel + OTLP -> Tempo; trace.id in ECS logs -> Loki (Alloy); Kafka headers carry traceparent
THREADS   spring.threads.virtual.enabled=true (all 4); DB pool is the real limit (DB_POOL_SIZE=5 in K8s)
PROBES    readiness = readinessState + db (NOT inventory) ; liveness separate
METRICS   orders_total{status}, inventory_reservations_rejected_total, outbox_unpublished, notifications_sent_total, payments_total{status}, payments_duplicates_total, http histograms
ALERTS    HighErrorRate, HighP99Latency, InventoryCircuitOpen, ServiceDown, OutboxBacklogGrowing, KafkaConsumerLagHigh, PaymentFailureRateHigh (FAILED ratio > 0.5 for 10m)
ARCH      ArchUnit (7 rules): controllers !-> *Repository/@Entity, *Service !-> spring web/servlet, *Controller naming, no field injection, no System.out, no j.u.logging
DEPLOY    layered non-root image, compose (+Kafka/Redis/Keycloak/Tempo/Loki), Kustomize dev/prod (+ generic Helm chart helm/shopflow-service), HPA, PDB, NetPol, Ingress; prod = RDS/MSK/ElastiCache/Cognito
LOADTEST  k6 loadtest/flash-sale.js: 300 buyers vs 3 PS5 + 200 rps browse; orders_confirmed count==3, order p95<800ms, browse p99<300ms, http_req_failed<1% ; CI: k6 inspect
CI/CD     verify (46 tests) -> kubeconform/promtool/terraform validate -> build x4 -> Trivy -> CycloneDX SBOM -> push GHCR (sha) -> cosign keyless sign -> pin prod overlay -> Argo CD
GAPS      no refund for PAID cancel, no timeout from CONFIRMED (payment-service down), per-pod rate limit, H2 not real PG, no jitter, MSK config never run against real MSK
NEXT      event-driven reservation, refund flow, edge rate limit, Schema Registry, Testcontainers
```
