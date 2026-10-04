# 03 — Microservice Patterns Interview Q&A

> Every pattern below is marked:
> **[In ShopFlow]** = implemented in `shopflow/` (with the file/config key), **[Partly]** = a simple version exists, **[Not in project]** = how you'd add it and what to say.

| Pattern | Status in ShopFlow |
|---|---|
| Database per service | **In ShopFlow** — `orders` DB vs `inventory` DB, separate Flyway migrations |
| Sync REST between services | **In ShopFlow** — `InventoryClient` (`RestClient`) |
| Timeouts | **In ShopFlow** — `inventory.connect-timeout: 1s`, `inventory.read-timeout: 2s` |
| Retry with exponential backoff | **In ShopFlow** — `resilience4j.retry.instances.inventory` (no jitter) |
| Circuit breaker | **In ShopFlow** — `resilience4j.circuitbreaker.instances.inventory` |
| Idempotency | **In ShopFlow** — `Idempotency-Key` header + `orderRef` |
| Saga / compensation | **Partly** — orchestrated by `OrderService`, sync, compensation = cancel → release; notifications are choreographed via events |
| Health probes / graceful shutdown | **In ShopFlow** — Actuator probes, `server.shutdown: graceful` |
| Metrics + alerts | **In ShopFlow** — Prometheus + custom counters; `shopflow/monitoring/alert-rules.yml` (error rate, p99, breaker open, target down, outbox backlog, consumer lag) |
| Externalised config (12-factor) | **In ShopFlow** — `DB_URL`, `DB_PASSWORD`, `INVENTORY_URL`, `DB_POOL_SIZE`, `KAFKA_BOOTSTRAP_SERVERS`, `JWT_ISSUER_URI`, `REDIS_HOST`, `OTLP_ENDPOINT` env vars, set by K8s Deployments + ConfigMap `shopflow-endpoints` + Secret `shopflow-db` |
| Containers + orchestration | **In ShopFlow** — `shopflow/Dockerfile`, `docker-compose.yml`, `k8s/` (Kustomize base + dev/prod overlays, HPA, PDB) |
| Network segmentation | **In ShopFlow (infra)** — `k8s/base/network-policies.yaml` (default deny ingress + egress, allow-list incl. Kafka/Redis/Keycloak) |
| Kafka / async messaging | **In ShopFlow** — topic `orders.events` (key `orderRef`), producer `acks=all` + idempotence, consumer group `notification-service` → see [10](10-kafka-event-driven.md) |
| Transactional outbox | **In ShopFlow** — `outbox_event` table, `OrderService.saveWithEvent` (`TransactionTemplate`), `OutboxRelay` (`@Scheduled`, `FOR UPDATE SKIP LOCKED`), gauge `outbox_unpublished` |
| Idempotent consumer / DLT | **In ShopFlow** — `processed_event` table in `OrderEventListener` (`@KafkaListener` + `@Transactional`); `DefaultErrorHandler` + `ExponentialBackOff` → `orders.events.DLT` |
| Rate limiting | **In ShopFlow** — `@RateLimiter(name = "orders")` on `OrderController.create`, 50/s per pod, `timeout-duration: 0` → 429 ProblemDetail |
| Bulkhead | **Not in project** |
| API gateway | **Partly** — NGINX Ingress `k8s/base/ingress.yaml` does path routing + TLS (prod); auth is in the service, rate limiting is per pod, nothing at the edge |
| Service discovery | **In ShopFlow via K8s DNS** — `INVENTORY_URL=http://inventory-service:8082` in the order Deployment; no Eureka |
| Config server | **Not in project** (env vars + ConfigMap + Secrets instead) |
| Distributed tracing | **In ShopFlow** — `micrometer-tracing-bridge-otel` + OTLP exporter → Tempo; `observation-enabled` on KafkaTemplate/listener; Grafana traces↔logs (Loki) → see [11](11-security-caching-performance.md) |
| Security (OAuth2/JWT, mTLS) | **In ShopFlow (order-service)** — OAuth2 resource server, stateless JWT, roles from Keycloak `realm_access.roles` / Cognito `cognito:groups` (`JwtRolesConverter`); no mTLS; inventory/notification protected by NetworkPolicy only |
| Caching (Redis) | **In ShopFlow (inventory-service)** — `@Cacheable("products")` DTO by `sku`, `@CacheEvict` on reserve/release, `CACHE_TYPE=simple|redis`, TTL 60s |

---

## 1. Monolith vs microservices

| | Monolith (or modular monolith) | Microservices |
|---|---|---|
| Deploy | one unit | each service independently |
| Scaling | whole app | per service |
| Data | one DB, ACID across modules | DB per service, eventual consistency |
| Failure | in-process calls don't fail on network | partial failures, timeouts, retries everywhere |
| Team | fine for small teams | fits many teams owning services (Conway's law) |
| Ops cost | low | high: CI/CD per service, observability, tracing, K8s |
| Debugging | stack trace | distributed tracing + correlated logs |

**Q: When would you choose microservices?**
When multiple teams need to deploy independently, parts have very different scaling/availability needs, or domains are clearly bounded. Not for a small team or an unclear domain — start with a **modular monolith** with clean module boundaries and split later.

**Q: How do you decide service boundaries?**
DDD **bounded contexts** / business capabilities: Orders, Inventory, Payments, Shipping. A service owns its data and its rules. If two services always change together or need to share tables, the boundary is wrong. In ShopFlow: inventory owns stock and reservation rules; order owns order lifecycle.

**Q: What is a distributed monolith?**
Services that are deployed separately but are tightly coupled: shared DB, synchronous call chains, must be released together. You get the costs of both and the benefits of neither.

**Q: Downsides of microservices?**
Network latency and failures, data consistency, distributed debugging, operational complexity, testing contracts across services, more infrastructure cost.

---

## 2. Database per service

**[In ShopFlow]** order-service: `spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/orders}`; inventory-service: `.../inventory`. Each has its own `db/migration`. Order stores only `sku` and `orderRef` strings — no FK to inventory tables.

**Q: Why not share one database?**
Shared schema = hidden coupling: one team's migration breaks another service, no independent scaling, can't change technology. Each service must be the **only** writer/reader of its tables; others go through its API or events.

**Q: Then how do you do a "join" across services?**
API composition (call both and merge — fine for small data), or **CQRS read model**: subscribe to events and maintain a denormalised view in the querying service.

**Q: How do you keep data consistent across services?**
No cross-DB ACID transactions. Use sagas (local transactions + compensations), idempotent operations, outbox for reliable events, and accept **eventual consistency** with clear intermediate states (ShopFlow: PENDING/FAILED).

**Q: Can two services share a DB server?**
Yes — same Postgres instance, different databases/schemas and credentials is fine (cost saving). The rule is about ownership, not hardware.

---

## 3. Synchronous REST vs asynchronous messaging

| | Sync (REST/gRPC) | Async (Kafka/RabbitMQ) |
|---|---|---|
| Coupling | temporal: both must be up | decoupled in time |
| Latency | immediate answer | eventual |
| Failure | caller must handle timeouts/retries | broker buffers; consumer catches up |
| Use | queries, need answer now (price, auth) | events, workflows, fan-out, spikes |
| ShopFlow | **order → inventory reserve/release** | not used |

**Q: Why is ShopFlow synchronous?**
The user wants to know immediately whether stock is reserved, and it keeps the project simple. The cost: if inventory is down, ordering is down (we fail fast with 503 and FAILED status). With Kafka we could accept the order as PENDING and confirm asynchronously. Where ShopFlow **does** use Kafka: side effects that must not slow down or fail the order — the notification. Every final order state is written to the outbox and published to `orders.events`; notification-service consumes it.

### Kafka basics [In ShopFlow — one topic, one consumer group; full notes in 10-kafka-event-driven.md]

```
Topic "orders" ── partition 0: [o0][o1][o2][o3] ...  offsets 0,1,2,3
               ── partition 1: [o0][o1] ...
               ── partition 2: [o0][o1][o2] ...

Producer: key = orderRef → murmur2(key) % partitions → same order always same partition (ordering per key)
Consumer group "inventory-service": each partition assigned to exactly ONE consumer in the group
  3 partitions, 2 pods → pod A: p0,p1  pod B: p2      (max useful consumers = partitions)
Another group "email-service" reads the same topic independently (pub/sub)
```

| Term | Meaning |
|---|---|
| Topic | named, append-only log of records |
| Partition | unit of ordering & parallelism; ordered only **within** a partition |
| Offset | position of a record in a partition; consumer commits the offset it has processed |
| Consumer group | set of consumers sharing work; each partition → one consumer in the group |
| Rebalance | partitions reassigned when consumers join/leave/crash |
| Replication factor | copies of each partition on different brokers; `min.insync.replicas` + `acks=all` for durability |
| Retention | records kept by time/size, not deleted on consume (replayable) |
| Compacted topic | keeps the last value per key |

Delivery semantics:
- **At-most-once**: commit offset before processing → crash = lost message.
- **At-least-once** (common default): process then commit → crash after processing but before commit = **duplicate** → consumers must be **idempotent** (ShopFlow's `orderRef` dedupe would make inventory a safe consumer).
- **Exactly-once**: idempotent producer (`enable.idempotence=true`) + transactions (`read_committed`) within Kafka; end-to-end with external DBs still needs idempotent consumers or dedupe tables.

**Q: How do you keep ordering in Kafka?**
Use a message key (e.g. `orderRef`) so all events of one entity go to the same partition; one consumer processes a partition sequentially. No global ordering across partitions.

**Q: Consumer is slow / lag growing — what do you do?**
Check consumer lag metrics; add consumers up to the number of partitions; increase partitions (affects key→partition mapping); speed up processing (batching, fewer remote calls); check for poison messages blocking a partition.

**Q: What is a poison message and how to handle it?**
A message that always fails processing. Retry a few times with backoff, then send to a **dead-letter topic (DLT)** and alert. Spring Kafka: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`.

**Q: Kafka vs RabbitMQ?**
Kafka: distributed log, replay, very high throughput, ordering per partition, consumers pull and track offsets. RabbitMQ: traditional broker, flexible routing (exchanges), per-message ack, messages removed after ack. Kafka for event streaming/event sourcing; RabbitMQ for task queues and complex routing.

**Q: Spring code?** (the real ShopFlow shape)
```java
// producer — OutboxRelay: key = orderRef (aggregateId), value = JSON payload from the outbox row
kafkaTemplate.send(OrderEvent.TOPIC, event.getAggregateId(), event.getPayload()).get(5, TimeUnit.SECONDS);
event.markPublished();

// consumer — OrderEventListener (notification-service)
@KafkaListener(topics = "orders.events", groupId = "notification-service")
@Transactional
public void onOrderEvent(String payload) throws Exception {
    OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
    if (processedEvents.existsById(event.eventId())) return;        // duplicate delivery
    notify(event);
    processedEvents.save(new ProcessedEvent(event.eventId(), event.orderRef()));
}
```
Config: producer `acks: all`, `enable.idempotence: true`; consumer `enable-auto-commit: false`, `auto-offset-reset: earliest`, `isolation.level: read_committed`; `DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOff(500ms, ×2, max 10s))` → `orders.events.DLT`.

---

## 4. Saga pattern

A saga = sequence of local transactions; each step has a **compensating action** to undo it if a later step fails.

| | Choreography | Orchestration |
|---|---|---|
| Control | services react to each other's events | a central orchestrator tells each service what to do |
| Coupling | loose, but flow is implicit and spread out | flow is explicit in one place |
| Visibility/debugging | hard ("who reacts to what?") | easy — state machine in orchestrator |
| Good for | 2–4 simple steps | many steps, complex compensation |
| Tools | Kafka events | code state machine, Temporal, Camunda, Axon |

**[Partly in ShopFlow]** `OrderService` is a simple **synchronous orchestrator**:

```
placeOrder:   save PENDING (local tx, orders DB)
              → inventory.reserve(orderRef)       (local tx, inventory DB)
              → CONFIRMED | REJECTED | FAILED     (local tx, orders DB)
cancel:       CONFIRMED|FAILED → inventory.release(orderRef)  (compensation, idempotent)
              → CANCELLED
```

**Q: Choreography version of ShopFlow?**
order-service publishes `OrderPlaced` → inventory consumes, reserves, publishes `StockReserved` or `StockRejected` → order-service consumes and sets CONFIRMED/REJECTED. Payment would listen to `StockReserved`, and on `PaymentFailed` inventory releases (compensation).

**Q: Requirements for saga steps?**
Each local step and compensation must be **idempotent** (messages/requests can repeat) and compensations must be **retryable** until they succeed. Steps should be designed so compensation is possible (e.g. "reserve" not "ship").

**Q: What are the weaknesses of sagas?**
No isolation: other transactions can see intermediate states (e.g. stock reserved for an order that will be cancelled). Countermeasures: semantic locks (status PENDING), commutative updates, re-reading values, pivot transactions.

**Q: What if compensation itself fails?**
Retry with backoff (it's idempotent); persist "compensation pending" state; alert and manual intervention if it keeps failing. In ShopFlow, if release fails, cancel returns 503 and the order stays CONFIRMED/FAILED, so the user/job can retry.

**Q: Saga vs 2PC?**
2PC gives atomicity but blocks resources while waiting on the coordinator, reduces availability, and isn't supported across HTTP APIs/most brokers. Sagas trade isolation for availability and loose coupling.

---

## 5. Transactional outbox [In ShopFlow — `order-service/.../outbox/`, `V2__outbox.sql`]

Problem — **dual write**:
```java
orderRepository.save(order);           // DB commit OK
kafkaTemplate.send("orders", event);   // crash here → order exists, event never sent
```
Reversing the order has the opposite problem (event sent, DB rolled back).

Solution: write the event into an `outbox` table **in the same local transaction**, then publish asynchronously.

```sql
-- V2__outbox.sql (actual)
create table outbox_event (
    id uuid primary key, aggregate_type varchar(32) not null, aggregate_id varchar(64) not null,
    event_type varchar(64) not null, payload text not null, created_at timestamp not null, published_at timestamp
);
create index idx_outbox_pending on outbox_event (published_at, created_at);
```
```java
// OrderService.saveWithEvent (actual) — TransactionTemplate, not @Transactional: the method is private and
// self-invoked from placeOrder/cancel, so an annotation would be bypassed by the proxy
private Order saveWithEvent(Order order) {
    Order saved = transactionTemplate.execute(status -> {
        Order persisted = orderRepository.save(order);
        OrderEvent event = OrderEvent.from(persisted);                       // OrderConfirmed / OrderRejected / OrderFailed / OrderCancelled
        outboxRepository.save(new OutboxEvent("Order", persisted.getOrderRef(), event.type(), toJson(event)));
        return persisted;                                                    // both rows commit atomically
    });
    count(saved);
    return saved;
}
```
Relay options:
1. **Polling publisher** (**ShopFlow**: `OutboxRelay`): `@Scheduled(fixedDelayString = "${outbox.relay.delay:1s}")` + `@Transactional` reads up to 100 unpublished rows oldest-first with `@Lock(PESSIMISTIC_WRITE)` + hint `jakarta.persistence.lock.timeout = -2` (= **`FOR UPDATE SKIP LOCKED`** on PostgreSQL, so several pods never publish the same row), sends each to Kafka with key = `aggregateId`, waits for the `acks=all` ack (`.get(5s)`), marks `published_at`; on the first failure it stops the batch to keep ordering. A gauge `outbox.unpublished` feeds the `OutboxBacklogGrowing` alert.
2. **CDC (Debezium)** reads the Postgres WAL and streams outbox rows to Kafka (Outbox Event Router) — lower latency, no polling, more infrastructure.

Delivery is **at-least-once** (crash after send, before marking) → consumers dedupe by **event id** (not `orderRef`: one order emits several events). The mirror pattern on the consumer side is the **inbox** — ShopFlow's `processed_event` table in notification-service.

**Q: What does the outbox fix in ShopFlow, and what is still open?**
Fixed: "order saved but notification lost" (and the reverse) — the event is durable the moment the order commits; a Kafka outage only grows the backlog, users still get 201. Still open: a crash between "save PENDING" and "reserve" leaves a stuck PENDING order, and a timeout leaves a FAILED order with unknown stock, because the **reservation** is still a synchronous call. Extending the outbox to an `OrderPlaced` event consumed by inventory (already idempotent by `orderRef`) would close that too.

---

## 6. Idempotency

**[In ShopFlow]**

| Where | Mechanism | Behaviour |
|---|---|---|
| `POST /api/v1/orders` | `Idempotency-Key` header → `orders.idempotency_key` UNIQUE → `findByIdempotencyKey` | replay → 200 + original order, inventory not called again |
| `POST /api/v1/reservations` | `orderRef` → `reservation.order_ref` UNIQUE → `findByOrderRef` | replay → returns existing reservation, no second decrement |
| `DELETE /api/v1/reservations/{orderRef}` | only RESERVED → RELEASED changes stock | second release / unknown ref = no-op 204 |
| Concurrent first requests (same `orderRef`) | loser's insert hits the unique key → its transaction (incl. the decrement) rolls back → controller returns the **winner's** reservation | both callers get 201 with the same reservation, stock moved once |

**Q: Why is idempotency the foundation of resilience?**
Networks are unreliable — a timeout doesn't tell you whether the server did the work. Retrying is only safe if doing it twice has the same effect as once. Retries, at-least-once messaging, and saga compensation all depend on it.

**Q: Natural vs synthetic idempotency keys?**
Natural: a business id that's already unique (`orderRef` for reservations). Synthetic: client-generated UUID per user action (`Idempotency-Key`). Generate it once per checkout attempt, reuse on retries.

**Q: What should be stored with the key?**
At minimum the resulting resource id (ShopFlow stores the key on the order row). Better: request hash (reject same key with different body — 422), response snapshot, and an expiry/TTL. ShopFlow doesn't check the body or expire keys — mention as improvement.

**Q: Idempotent consumer in Kafka?**
Keep a processed-events table with unique event id, insert it in the same transaction as the business change; duplicate → found/unique violation → skip. Or make the operation naturally idempotent (upsert, "set status = X"). **ShopFlow**: `OrderEventListener` checks `processedEvents.existsById(eventId)`, otherwise notifies and saves `ProcessedEvent` inside the same `@Transactional`; test `processesEachEventExactlyOnceEvenWhenDeliveredTwice`.

---

## 7. Retries with backoff and jitter

**[In ShopFlow]** `order-service/src/main/resources/application.yml`:
```yaml
resilience4j.retry.instances.inventory:
  max-attempts: 3                      # 1 call + 2 retries
  wait-duration: 200ms                 # 200ms, then 400ms
  enable-exponential-backoff: true
  exponential-backoff-multiplier: 2
  retry-exceptions:
    - org.springframework.web.client.ResourceAccessException
    - org.springframework.web.client.HttpServerErrorException
```
Business rejections (`InventoryRejectedException` for 409/404) are **not** in the list → not retried. Tests: `retriesTransientInventoryErrors`, `rejectsOrderWhenOutOfStockWithoutRetrying`, `returns503AndMarksOrderFailedWhenInventoryIsDown` (3 calls).

Rules:
1. Retry only **transient** errors: timeouts, connection refused, 502/503/504, 429 (respect `Retry-After`).
2. Never retry 400/401/403/404/409/422.
3. Only retry **idempotent** operations (or make them idempotent).
4. **Exponential backoff** — give the system time to recover.
5. **Jitter** — randomise waits so 1,000 clients don't retry at the same instant (thundering herd). ShopFlow has no jitter yet; add:
   ```yaml
   enable-exponential-backoff: true
   enable-randomized-wait: true    # with both enabled -> exponential random backoff
   randomized-wait-factor: 0.5     # each wait = computed wait ± 50%
   ```
   (Programmatic equivalent: `IntervalFunction.ofExponentialRandomBackoff(200ms, 2.0, 0.5)`. Verify property support for your exact Resilience4j version.)
6. **Cap attempts and total time**; retry at **one layer** only (if gateway, service A and service B each retry 3×, a request can become 27 calls → retry amplification).
7. Combine with a circuit breaker so retries stop when the dependency is clearly down.

**Q: Where should retries live — client library, gateway, or mesh?**
One place, closest to the knowledge of idempotency. ShopFlow does it in the caller's client (`InventoryClient`). A mesh (Istio) can do it too, but then disable app-level retries for that call.

---

## 8. Circuit breaker

**[In ShopFlow]**
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

```
          failure rate >= 50% (after >= 5 calls in window of 10)
 CLOSED ───────────────────────────────────────────────► OPEN  (fail fast: CallNotPermittedException)
   ▲                                                       │
   │ trial calls succeed                    after 10s      │
   │                                                       ▼
   └──────────────────────────────────────────────── HALF_OPEN (2 trial calls)
                     trial calls fail → back to OPEN
```

Also in Resilience4j: slow-call detection (`slow-call-duration-threshold`, `slow-call-rate-threshold`), TIME_BASED windows, states DISABLED / FORCED_OPEN.

Handling in ShopFlow: `CallNotPermittedException` caught in `OrderService.placeOrder` → order FAILED → 503; in `GlobalExceptionHandler.handleDownstream` for the cancel path. Test `circuitBreakerOpensAndFailsFast` asserts state OPEN and **zero** HTTP calls afterwards.

**Q: Why a circuit breaker if you already have timeouts?**
A timeout still costs up to 2s × 3 attempts per request and holds a thread. When inventory is dead, the breaker short-circuits in microseconds, protects our threads, and gives inventory room to recover.

**Q: Why ignore business exceptions?**
A 409 "out of stock" means inventory is healthy. Counting it as failure would open the circuit during a flash sale and block all orders.

**Q: Order of Retry and CircuitBreaker?**
Resilience4j default: `Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )`. So each retry attempt is recorded by the breaker, and when the breaker is open the retry sees `CallNotPermittedException` (not retryable in ShopFlow) and stops.

**Q: Fallback options when open?**
Return cached/default data, queue the request for later, degrade a feature (hide recommendations), or fail fast with a clear 503 (ShopFlow — stock reservation can't be faked).

**Q: How do you monitor it?**
Resilience4j publishes Micrometer metrics — `resilience4j_circuitbreaker_state{state=...}` (1 for the current state), `resilience4j_circuitbreaker_calls_seconds_count{kind="successful|failed|ignored"}`, `resilience4j_circuitbreaker_not_permitted_calls_total`, `resilience4j_circuitbreaker_failure_rate` → Prometheus alert when state = open. ShopFlow has exactly this: `InventoryCircuitOpen` in `monitoring/alert-rules.yml` — `resilience4j_circuitbreaker_state{name="inventory", state="open"} == 1` for 1m, severity `page`.

---

## 9. Bulkhead [Not in project]

Isolate resources per dependency so one slow dependency can't consume all threads (like watertight compartments in a ship).

- **Semaphore bulkhead**: max N concurrent calls; extra calls wait briefly or are rejected.
- **Thread-pool bulkhead**: separate pool + queue per dependency (async).

```yaml
resilience4j.bulkhead.instances.inventory:
  max-concurrent-calls: 20
  max-wait-duration: 0ms
```
```java
@Bulkhead(name = "inventory")
@Retry(name = "inventory")
@CircuitBreaker(name = "inventory")
public void reserve(...) { ... }
```
**Q: Why would ShopFlow want it?** With 200 Tomcat threads and inventory hanging, up to 200 threads can wait 2s each — `GET /orders` would starve too. A bulkhead of 20 keeps 180 threads for everything else. Also: K8s resource limits and separate connection pools are infrastructure bulkheads.

---

## 10. Rate limiting [In ShopFlow — `@RateLimiter(name = "orders")` on `OrderController.create`]

Protect services from overload/abuse; enforce fair usage per client.

| Algorithm | Idea |
|---|---|
| Token bucket | bucket refills at rate r, holds max b tokens; allows bursts (most common) |
| Leaky bucket | fixed outflow rate, smooths bursts |
| Fixed window | N requests per minute window; burst at window edges |
| Sliding window log/counter | more accurate, more memory |

- Where: API gateway / Ingress (per IP, per API key, per user), or in-app (Resilience4j `@RateLimiter`, Bucket4j).
- Distributed limit across pods → shared store (Redis) — e.g. Spring Cloud Gateway `RequestRateLimiter` with Redis.
- Response: **429 Too Many Requests** + `Retry-After` header.
- **ShopFlow**: `resilience4j.ratelimiter.instances.orders: limit-for-period: 50, limit-refresh-period: 1s, timeout-duration: 0` — 50 order creations per second **per pod**, no queueing; `RequestNotPermitted` → `GlobalExceptionHandler.handleRateLimited` → 429 ProblemDetail "Too many requests" (test `rateLimitExceededIs429`, a `@WebMvcTest` slice). Only the expensive write path is limited. Honest limits: per pod (N pods = N × 50), not per user, no `Retry-After` yet — a gateway/Redis limiter is the next step.

**Q: Rate limiter vs circuit breaker vs bulkhead?**
Rate limiter: limits **incoming rate** (protect myself / downstream from too many requests per time). Bulkhead: limits **concurrency** to a dependency. Circuit breaker: stops calls to a **failing** dependency.

---

## 11. Timeouts

**[In ShopFlow]** `InventoryClient` constructor:
```java
var requestFactory = new SimpleClientHttpRequestFactory();
requestFactory.setConnectTimeout(properties.connectTimeout());   // 1s
requestFactory.setReadTimeout(properties.readTimeout());         // 2s
this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
```

**Q: Connect vs read timeout?**
Connect: time to establish TCP connection (host down / wrong address). Read: max wait for data on an open connection (server slow / hung). Both are needed; default for many clients is infinite.

**Q: How do you choose timeout values?**
From the downstream's latency SLO: a bit above its p99 (e.g. p99 = 300ms → 1s). Mind the **total budget**: caller timeout must be larger than callee timeout × attempts + backoff. ShopFlow worst case ≈ 3 × 2s + 0.6s ≈ 6.6s.

**Q: What is deadline propagation?**
Pass the remaining time budget downstream (gRPC deadlines, or a header) so deep services don't keep working on requests the edge has already given up on.

**Q: Other timeouts to set?**
DB: Hikari `connection-timeout`, statement/query timeout; transaction timeout (`@Transactional(timeout = 5)`); gateway/Ingress timeouts; Kafka consumer `max.poll.interval.ms`.

---

## 12. API gateway [Partly: Ingress only]

Single entry point in front of services: routing, authentication (validate JWT once), rate limiting, CORS, TLS termination, request/response transformation, aggregation (BFF), canary routing.

Options: **Spring Cloud Gateway**, Kong, NGINX, AWS API Gateway, Kubernetes Ingress / Gateway API (NGINX Ingress, Traefik, Istio gateway).

**[In ShopFlow]** `k8s/base/ingress.yaml` (ingressClassName `nginx`, host `shopflow.local`; prod overlay switches host to `shop.example.com` with TLS secret `shopflow-tls` from cert-manager):

| Path (Prefix) | Backend |
|---|---|
| `/api/v1/orders` | `order-service:http` |
| `/api/v1/products` | `inventory-service:http` |

`/api/v1/reservations` is **not** exposed by the Ingress rules. The NetworkPolicy adds an L3/L4 layer — inventory pods accept traffic only from order-service, the ingress-nginx and monitoring namespaces — but it cannot filter by URL path, so the path restriction itself comes from the Ingress. What's missing vs a real gateway: JWT validation, per-client rate limiting, API keys, request transformation. Cheapest next step: NGINX Ingress annotations (`nginx.ingress.kubernetes.io/limit-rps`), or put Spring Cloud Gateway / Kong behind the Ingress.

```yaml
# Spring Cloud Gateway sketch for ShopFlow
# (prefix is spring.cloud.gateway.routes in older releases; Spring Cloud 2025.0+ moved it
#  to spring.cloud.gateway.server.webflux.routes)
spring.cloud.gateway.routes:
  - id: orders
    uri: http://order-service:8081
    predicates: [ "Path=/api/v1/orders/**" ]
    filters:
      - name: RequestRateLimiter          # Redis token bucket
        args:
          redis-rate-limiter.replenishRate: 10
          redis-rate-limiter.burstCapacity: 20
          key-resolver: "#{@userKeyResolver}"
  - id: products
    uri: http://inventory-service:8082
    predicates: [ "Path=/api/v1/products/**" ]
# /api/v1/reservations is NOT routed — internal only (called by order-service)
```

**Q: Gateway vs load balancer vs Ingress?**
Load balancer distributes traffic (L4/L7). Ingress = K8s L7 routing rules by host/path. API gateway = L7 + API concerns (auth, rate limits, transformations, API keys). Lines blur (Kong/NGINX can be all).

**Q: What is BFF (Backend for Frontend)?**
A gateway/service per client type (web, mobile) that aggregates and shapes data for that client.

**Q: Risks?**
Single point of failure (run multiple replicas), bottleneck, and "god gateway" with business logic — keep it thin.

---

## 13. Service discovery [In ShopFlow via Kubernetes DNS — no Eureka]

ShopFlow: the inventory address is config — `inventory.base-url: ${INVENTORY_URL:http://localhost:8082}`. In Kubernetes, `k8s/base/order-service/deployment.yaml` sets `INVENTORY_URL=http://inventory-service:8082` (the ClusterIP Service in `k8s/base/inventory-service/service.yaml`); in Docker Compose the service name `inventory-service` resolves the same way.

| | Eureka (Spring Cloud Netflix) | Kubernetes DNS |
|---|---|---|
| How | services register on startup, heartbeat; clients fetch registry and load-balance client-side (Spring Cloud LoadBalancer) | `Service` object gets stable virtual IP + DNS `inventory-service.<ns>.svc.cluster.local`; kube-proxy load-balances to ready pods |
| Extra infra | Eureka server cluster | none (built-in) |
| Health | heartbeats | readiness probes decide endpoints |
| Use | VMs / non-K8s | anything on K8s |

**Q: What would you do for ShopFlow on Kubernetes?**
Exactly what it does: a ClusterIP Service `inventory-service` and `INVENTORY_URL=http://inventory-service:8082` on the Deployment. Readiness probes decide which inventory pods are behind the Service. No Eureka needed. (The repo's older `Devops/` backend calls `helper-service` the same way.)

**Q: Client-side vs server-side discovery?**
Client-side: client queries registry and chooses an instance (Eureka + LoadBalancer). Server-side: client calls a stable address; a load balancer/proxy chooses (K8s Service, AWS ALB).

**Q: Problem with K8s Services and long-lived connections?**
kube-proxy balances per **connection**, so HTTP/2/gRPC keep-alive connections can stick to one pod. Use client-side LB (headless Service) or a service mesh.

---

## 14. Config server vs ConfigMaps [Partly: env vars + Secrets]

**[In ShopFlow]** Config is externalised via env vars with defaults (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `INVENTORY_URL`) and typed with `@ConfigurationProperties` (`InventoryProperties`). In K8s, non-secret values are plain `env` entries in the Deployments (no ConfigMap yet) and passwords come from Secret `shopflow-db`: generated by Kustomize `secretGenerator` in `overlays/dev`, synced from AWS Secrets Manager / Vault by External Secrets Operator in prod (per the prod overlay comment).

| | Spring Cloud Config Server | K8s ConfigMap / Secret |
|---|---|---|
| Source | Git repo (versioned, auditable) | K8s objects (manifests in Git via GitOps) |
| Runtime refresh | `@RefreshScope` + `/actuator/refresh` / Spring Cloud Bus | mounted files update; env vars need pod restart |
| Extra infra | config server (HA needed) | none |
| Secrets | encryption support / Vault backend | Secrets (base64, not encrypted by default — enable encryption at rest or use External Secrets / Vault / Sealed Secrets) |

**Q: Which would you pick?**
On Kubernetes: ConfigMaps + Secrets (+ External Secrets Operator for Vault/AWS SM), deploy changes via rolling restart (or checksum annotation). Config Server makes sense outside K8s or when you need runtime refresh across many services.

```yaml
# Actual: k8s/base/order-service/deployment.yaml (trimmed)
env:
  - name: DB_URL
    value: jdbc:postgresql://postgres:5432/orders
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef: { name: shopflow-db, key: orders-db-password }
  - name: INVENTORY_URL
    value: http://inventory-service:8082     # K8s DNS name of the Service
  - name: LOGGING_STRUCTURED_FORMAT_CONSOLE
    value: ecs                               # JSON logs
```
Possible refactor: move non-secret values into a ConfigMap and use `envFrom: [{configMapRef: {name: order-config}}]`, so overlays can change them with a `configMapGenerator` (its hash suffix triggers a rolling restart automatically).

---

## 15. Distributed tracing [In ShopFlow — parent `pom.xml`, `management.tracing.*`, Tempo/Loki/Alloy in compose]

- **Trace** = one end-to-end request; **span** = one operation (HTTP server handling, HTTP client call, DB query, Kafka send/receive). Each span has `traceId` (shared), `spanId`, `parentSpanId`.
- Context is propagated in headers — W3C `traceparent: 00-<traceId>-<spanId>-01` (or B3); for Kafka, in record headers.
- Spring Boot 3: **Micrometer Tracing** (replaced Spring Cloud Sleuth) with a bridge to **OpenTelemetry** or Brave; export to Zipkin / Jaeger / Tempo via OTLP.

As configured in ShopFlow (all three services via the parent pom):
```xml
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-bridge-otel</artifactId></dependency>
<dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-exporter-otlp</artifactId></dependency>
```
```yaml
management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}        # 100% locally; 0.1 is typical in prod
management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}   # compose: tempo:4318, K8s: from shopflow-endpoints ConfigMap
spring.kafka.template.observation-enabled: true    # order-service: trace context travels in the Kafka headers
spring.kafka.listener.observation-enabled: true    # notification-service: continue the trace that started in order-service
```
Tests set `management.otlp.tracing.export.enabled: false`. `InventoryClient` builds its `RestClient` from the **auto-configured `RestClient.Builder`** (comment in the class), which is instrumented with observations — so `traceparent` is propagated to inventory automatically; a `new RestClient` without the builder would lose that. Logs: Boot's ECS structured logging (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) includes `trace.id`/`span.id`; Grafana's Loki datasource turns them into links to Tempo (`derivedFields`), and Tempo's `tracesToLogsV2` goes the other way. Details and the agent-vs-Micrometer comparison: [11](11-security-caching-performance.md).

**Q: Logs vs metrics vs traces?**
Metrics: aggregated numbers, cheap, for alerting ("p99 is up"). Traces: one request's path across services ("which hop is slow"). Logs: detailed events ("what exactly happened"); correlate them via `traceId`.

**Q: What is sampling?**
Recording only a percentage of traces to control cost. Head-based (decided at start) vs tail-based (collector keeps slow/error traces).

**Q: Correlation ID without full tracing?**
Generate an id at the edge, put it in MDC (`%X{correlationId}` in log pattern) and forward it in a header. Tracing does this for you with `traceId`.

---

## 16. CAP theorem & eventual consistency

- **CAP**: during a network **P**artition, a distributed system must choose **C**onsistency (refuse/err) or **A**vailability (answer, maybe stale). Without partitions you can have both; **PACELC** adds: Else, trade Latency vs Consistency.
- **Eventual consistency**: replicas/services converge if no new updates; clients may see intermediate states.

In ShopFlow terms:
- Inventory is the **consistency authority** for stock — the conditional UPDATE is strongly consistent within its DB.
- order-service chooses **consistency over availability for writes**: if inventory is unreachable, it does not confirm orders (FAILED + 503) rather than risk overselling. Reads (`GET /orders`) remain available (readiness excludes inventory).
- Across services the system is **eventually consistent**: a FAILED order may have a reservation until cancelled/reconciled.

**Q: Example where you'd choose availability?**
Product catalog/browsing — show slightly stale data from cache. Shopping cart (Amazon Dynamo paper). Likes/view counts.

**Q: How do you make eventual consistency acceptable to users?**
Explicit states ("Order received, confirming…"), notifications when confirmed, idempotent retries, reconciliation jobs, and compensations.

**Q: ACID vs BASE?**
ACID: atomic, consistent, isolated, durable transactions (inside one DB — ShopFlow's `ReservationService.reserve`). BASE: Basically Available, Soft state, Eventually consistent (across services).

---

## 17. 12-factor app

| Factor | ShopFlow |
|---|---|
| 1 Codebase | one Git repo, Maven multi-module |
| 2 Dependencies | declared in `pom.xml`, Maven wrapper (`mvnw`) |
| 3 Config | env vars `DB_URL`, `DB_PASSWORD`, `INVENTORY_URL`, `DB_POOL_SIZE` |
| 4 Backing services | Postgres / inventory URL are attachable resources via config |
| 5 Build, release, run | CI builds one immutable image per commit (tag = git SHA, plus `latest` on `main`); config is injected at deploy time via env/Secrets and Kustomize overlays. Gap: the prod overlay still references `latest` (dev uses locally built `:dev` images) — pinning the SHA, as the overlay comment describes, makes it true build-once/promote |
| 6 Processes | stateless services; state in Postgres |
| 7 Port binding | embedded Tomcat on 8081/8082 |
| 8 Concurrency | scale out by adding pods |
| 9 Disposability | fast start, `server.shutdown: graceful`, 20s shutdown phase |
| 10 Dev/prod parity | same Flyway migrations everywhere; gap: H2 in tests (fix with Testcontainers) |
| 11 Logs | SLF4J to stdout; JSON (ECS) in K8s via `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; aggregation (ELK/Loki) is the platform's job |
| 12 Admin processes | Flyway migrations run at startup; one-off jobs could be K8s Jobs |

---

## 18. Security [App-level: in ShopFlow (order-service) · Infra hardening: in ShopFlow]

order-service is an **OAuth2 resource server** (`SecurityConfig`, `JwtRolesConverter`; issuer `${JWT_ISSUER_URI}` = Keycloak realm `shopflow` locally, Cognito user pool in prod). inventory- and notification-service have no application-level auth and rely on infrastructure hardening: NetworkPolicies (default deny ingress + egress; inventory reachable only from order-service, ingress-nginx and monitoring; Postgres only from the three services; Kafka only from order/notification; Redis only from inventory; Keycloak from ingress + order-service), containers run as UID 10001 with `readOnlyRootFilesystem`, dropped capabilities and `seccompProfile: RuntimeDefault`, `automountServiceAccountToken: false`, Trivy image scanning in CI, and `/api/v1/reservations` is not routed by the Ingress. Full notes in [11](11-security-caching-performance.md). The essentials:

### OAuth2 / OIDC / JWT
- **Authorization server** (Keycloak, Auth0, Okta, Cognito) issues tokens. **Resource servers** (our services) validate them.
- Flows: Authorization Code + PKCE (users/SPAs/mobile), Client Credentials (service-to-service).
- JWT = header.payload.signature (base64url). Resource server verifies signature with the issuer's public keys (JWKS), checks `exp`, `iss`, `aud`, then reads `sub`, `scope`/roles.

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
```
```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: https://auth.example.com/realms/shopflow
```
```java
// SecurityConfig (actual): roles instead of scopes, converter maps Keycloak realm_access.roles / Cognito cognito:groups → ROLE_*
@Bean
SecurityFilterChain filterChain(HttpSecurity http, JwtRolesConverter rolesConverter) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())                                  // no cookies/session -> no CSRF surface
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyRole("customer", "support")
            .requestMatchers("/api/v1/orders/**").hasRole("customer")
            .anyRequest().denyAll())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter)))
        .build();
}
```
Tests: `mockMvc.perform(post(...).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))))`; no token → 401, `support` posting → 403 (`rejectsRequestsWithoutAValidToken`). Gaps to admit: no per-order ownership check, `aud` not validated.

**Q: JWT pros/cons?**
Pros: stateless validation, no session store, carries claims. Cons: can't revoke easily before expiry (keep access tokens short, 5–15 min, use refresh tokens), size, sensitive data must not go in the payload (it's only encoded, not encrypted).

**Q: Where to validate tokens — gateway or each service?**
Both (defence in depth): gateway rejects junk early; services still validate and enforce their own authorization (zero trust). Pass the original token or exchange it (token exchange) for downstream calls.

**Q: Service-to-service security?**
- **mTLS**: both sides present certificates; proves service identity and encrypts traffic. Usually done by a **service mesh** (Istio, Linkerd) with automatic cert rotation, plus authorization policies ("only order-service may call `/api/v1/reservations`").
- Or OAuth2 **client credentials** tokens with scopes like `inventory:reserve`.
- Plus K8s **NetworkPolicies** to restrict which pods can talk — **already in ShopFlow** (`k8s/base/network-policies.yaml`). Note: NetworkPolicies are L3/L4 (IP/port) only and need a CNI that enforces them (Calico/Cilium); they don't authenticate the caller like mTLS does.

**Q: Other security basics for ShopFlow?**
Secrets from K8s Secrets/Vault (`DB_PASSWORD` already externalised), don't expose `/swagger-ui.html` and `/actuator/prometheus` publicly, validate input (already Bean Validation), don't leak internals in errors (ProblemDetail handlers return generic messages for downstream/DB errors), dependency scanning (OWASP/Snyk/Trivy) in CI, run containers as non-root.

---

## 19. Caching [In ShopFlow — inventory-service]

- Spring Cache abstraction: `@EnableCaching` (`InventoryServiceApplication`), `@Cacheable(cacheNames = "products", key = "#sku")` on `ProductController.get` (caches the `ProductResponse` **DTO**, not the entity), `@CacheEvict(cacheNames = "products", key = "#sku")` on `ReservationService.reserve`, `@CacheEvict(allEntries = true)` on `release`. Backend: `spring.cache.type: ${CACHE_TYPE:simple}` (per-pod map in tests), `redis` in compose/K8s (shared across pods) with `time-to-live: 60s` and `key-prefix: "inventory:"`; Redis runs with `allkeys-lru`.
- Pattern: **cache-aside with evict-on-write** — the stock change is an atomic bulk `UPDATE`, so the service never knows the new quantity and cannot "put" it; evicting lets the next read fetch the truth. Test: `productReadIsCachedAndEvictedOnReserve`.
- **Never** use cached stock to decide a reservation; the atomic DB update stays the source of truth — the cache only serves the catalog `GET`.
- Serializer: `config/CacheConfig.java` registers a `RedisCacheManagerBuilderCustomizer` with `GenericJackson2JsonRedisSerializer`, so `ProductResponse` (a record, not `Serializable`) is stored as JSON instead of failing on JDK serialization; `CacheSerializationTest` pins it. Details: [11](11-security-caching-performance.md).

**Q: Cache invalidation strategies?** TTL (ShopFlow: 60s safety net), explicit evict on write (ShopFlow), event-driven eviction (publish `ProductUpdated`). Watch for stampede (many misses at once) → `sync = true` / request coalescing / jittered TTLs.

---

## 20. Scenario questions

**S1. Inventory-service is down. What happens in ShopFlow, and how would you improve it?**
Each order: up to 3 attempts (≈6.6s worst case), then FAILED + 503 with `orderId`. After ≥5 recorded failures with ≥50% failure rate the breaker opens → instant 503 for 10s, then half-open probes. `GET /orders` keeps working because readiness only checks `readinessState,db`. Improvement: accept orders as PENDING and reserve asynchronously via outbox + Kafka; add a reconciliation job for FAILED orders.

**S2. A customer was charged twice / has two orders after a mobile network glitch.**
Client retried a POST after a timeout. Fix: `Idempotency-Key` per checkout (ShopFlow supports it; make it mandatory), unique constraint, idempotent downstream calls (`orderRef`), and payment provider idempotency keys too.

**S3. Black Friday: 10× traffic, PS5 stock of 100, 50,000 buyers.**
Correctness: atomic conditional UPDATE prevents overselling (ShopFlow). Throughput: one hot row → lock contention. Options: queue requests (Kafka) and process sequentially per SKU, split stock across N "bucket" rows, pre-allocate tokens in Redis (`DECR`) then persist, rate-limit at the gateway, waiting room. Business rejections (409) must not open the breaker — ShopFlow ignores `InventoryRejectedException`. Scale order pods via HPA; watch DB connections (`pods × DB_POOL_SIZE`).

**S4. Latency of `POST /orders` jumped from 100ms to 3s.**
Trace (if added) or metrics: compare order-service server latency vs client-call latency to inventory. If inventory read latency ~2s → hitting `read-timeout`, retries adding more. Check inventory DB (locks on hot SKU rows, slow queries), Hikari pending, GC. Check retry metrics — every retry multiplies load. Short-term: lower timeouts/attempts; long-term: fix the slow path.

**S5. Service A → B → C → D chain; D is slow and everything times out.**
Cascading failure. Add timeouts with decreasing budgets down the chain, circuit breakers at each hop, bulkheads, fallbacks, and remove sync chains where possible (events/async, caching, data replication via events).

**S6. An event was published twice and inventory reserved twice.**
At-least-once delivery. Make the consumer idempotent — ShopFlow's `reserve` already returns the existing reservation for a known `orderRef`, enforced by a unique constraint. Generally: dedupe table keyed by event id.

**S7. Orders saved but the event never reached Kafka.**
Classic dual-write problem — ShopFlow avoids it with the transactional outbox (event row in the same DB transaction as the order, `OutboxRelay` publishes it). If it *still* happens: the relay is stuck or Kafka is down → `outbox_unpublished` grows and `OutboxBacklogGrowing` pages; check relay logs ("could not publish outbox event ... will retry"), broker health, `KAFKA_BOOTSTRAP_SERVERS`. Nothing is lost: when Kafka returns the relay drains the backlog oldest-first (8 scenarios in [10](10-kafka-event-driven.md)).

**S8. After a deployment, order-service pods are Ready but every order fails.**
Readiness only checks own DB (by design), so config errors to downstreams show up as request failures: check `INVENTORY_URL` (Deployment env), DNS, NetworkPolicy (`inventory-service-ingress` only allows pods labelled `app.kubernetes.io/name: order-service`), breaker state metric, logs "inventory unavailable for order …". Add a smoke test in the pipeline; consider a startup check for config validity (not for downstream availability).

**S9. How do you roll out a breaking API change in inventory without breaking order-service?**
Version the API (`/api/v2/reservations`) or make changes backward compatible (add optional fields, don't remove/rename), deploy provider first, then consumer, remove old version later. Consumer-driven contract tests (Spring Cloud Contract / Pact) in CI.

**S10. Database migration on a live system with multiple pods.**
Expand/contract: add new nullable column (V3) → deploy code writing both → backfill → switch reads → later migration drops old column. Never rename/drop in one step while old pods still run. Flyway runs at startup — with several pods, Flyway's schema history lock ensures only one applies it; heavy migrations better as a separate Job.

**S11. One pod of inventory-service is returning 500s, others are fine.**
Readiness should catch DB issues on that pod (`db` in readiness group) and remove it. If it's another issue (bad node, memory), retries *may* land on a healthy pod (not guaranteed — kube-proxy balances per connection and a keep-alive connection can hit the same pod), and the breaker is per client, not per pod: 1 bad pod of 3 ≈ 33% failures stays under the 50% threshold. Outlier detection (service mesh) is the real fix; investigate with per-pod metrics (`instance` label) and logs; liveness restarts only if the JVM is truly stuck.

**S12. How do you debug a request across services without tracing?**
Correlation id in a header + MDC in logs, centralised logging (ELK/Loki) and search by id. ShopFlow's `orderRef` is logged in all three services (`"reserved {} x {} for order {}"`, `"order {} {}"`, `"EMAIL -> customer: Your order {} ..."`) — it acts as a business correlation id for order flows, and since tracing was added the ECS logs also carry `trace.id`, so the "without tracing" case is now the fallback.

---

> Tip: Jab koi pattern project me nahi hai, toh bolo: "Not in my project yet — here is exactly where I'd add it and why." Ye honest + practical answer hamesha "haan maine kiya hai" bolne se better hota hai.
