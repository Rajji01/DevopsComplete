# ShopFlow — production-style microservices

A small e-commerce backend built the way real teams build services: Java 21 + Spring Boot 3.5, PostgreSQL, Kafka (saga choreography), Redis, OAuth2/JWT, Docker, Kubernetes (Kustomize **and** Helm), Terraform on AWS, CI/CD with SBOM + signing, k6 load test, full observability.

Problem it solves: **many customers buy the same limited-stock product at the same time (flash sale). Never oversell, never charge twice, notify the customer reliably, and stay up when a dependency is down.**

```
                 JWT (Keycloak locally / Amazon Cognito on AWS)
 client ──HTTPS──▶ Ingress / ALB
                     │ /api/v1/orders                         /api/v1/products
                     ▼                                              ▼
              ┌──────────────┐   REST (timeout, retry,     ┌───────────────────┐
              │ order-service│   circuit breaker, 429      │ inventory-service │──▶ Redis (cache-aside)
              │  :8081       │ ──────────────────────────▶ │  :8082            │
              └──────┬───────┘   POST /reservations         └─────────┬─────────┘
                     │ one DB tx: order + outbox row                  │ atomic UPDATE ... WHERE qty >= n
                     ▼                                                ▼
               Postgres: orders                                Postgres: inventory
                     │ relay (SKIP LOCKED)
                     ▼
               Kafka topic orders.events ──┬▶ notification-service :8083 ──▶ Postgres: notifications
                                            │  (idempotent consumer, DLT)      (processed_event)
                                            └▶ payment-service :8084 ──▶ Postgres: payments (+ outbox)
                                                 charge → PaymentCaptured / PaymentFailed
                                                            │ topic payments.events
                     ◀──────────────────────────────────────┘  order-service: PAID, or release + CANCELLED

   Prometheus (metrics) · Tempo (traces) · Loki (logs) · Grafana — one trace id links all three
```

## Services

| | order-service | inventory-service | notification-service | payment-service |
|---|---|---|---|---|
| Port | 8081 | 8082 | 8083 | 8084 |
| Owns | `orders` DB, `outbox_event`, `processed_event` | `inventory` DB (products, reservations), Redis cache | `notifications` DB (`processed_event`) | `payments` DB (`payment`, `outbox_event`) |
| API | `POST /api/v1/orders` (JWT role `customer`, optional `Idempotency-Key`)<br>`GET /api/v1/orders?page=&size=` (own orders; `support` sees all)<br>`GET /api/v1/orders/{id}` (owner or `support`, else 404)<br>`DELETE /api/v1/orders/{id}` (cancel own order) | `GET /api/v1/products`<br>`GET /api/v1/products/{sku}` (cached)<br>`POST /api/v1/reservations`<br>`DELETE /api/v1/reservations/{orderRef}` | none (consumes `orders.events`) | none (consumes `orders.events`, produces `payments.events`) |
| Docs | http://localhost:8081/swagger-ui.html | http://localhost:8082/swagger-ui.html | — | — |

### Placing an order, end to end

1. The client sends `POST /api/v1/orders` with a JWT. order-service validates signature/expiry/issuer against the issuer's JWKS and checks the `customer` role. Over 50 req/s per pod → `429`.
2. If the `Idempotency-Key` header was seen before, the stored order is returned (`200`). Otherwise the order is saved as `PENDING`.
3. order-service calls `POST /api/v1/reservations` on inventory-service: 1s/2s timeouts, up to 3 attempts with exponential backoff **only** for transient errors, behind a circuit breaker that opens at 50% failures.
4. inventory-service runs `UPDATE product SET quantity = quantity - n WHERE sku = ? AND quantity >= n` and inserts a reservation keyed by `orderRef`, in one transaction. Two buyers can never get the last unit; a retried request gets the existing reservation; two *concurrent* retries both get the winner's reservation. The product's cache entry is evicted.
5. The order becomes `CONFIRMED` (201), `REJECTED` (409/404 from inventory, never retried) or `FAILED` + HTTP 503 (inventory unreachable). **Order row and an `OrderConfirmed`/`OrderRejected`/… event are written in the same DB transaction** (transactional outbox).
6. Every second the outbox relay publishes unpublished rows to Kafka (`acks=all`, idempotent producer, key = `orderRef` so one order's events stay in order) and marks them published. At-least-once: a crash between send and mark re-sends.
7. notification-service consumes the event, checks `processed_event` (duplicate → skipped, counted), "sends the email" (a log line standing in for SES/FCM), and records the event id, all in one transaction. A message that keeps failing is retried with backoff, then moved to `orders.events.DLT`.
8. **payment-service** consumes `OrderConfirmed`, charges the card (a fake gateway that declines above ₹100,000), and writes the payment plus a `PaymentCaptured`/`PaymentFailed` event in one transaction (its own outbox). Idempotent: the payment's primary key is the event id.
9. order-service consumes `payments.events`: `PaymentCaptured` → **`PAID`**; `PaymentFailed` → release the reservation + `CANCELLED` ("payment failed: ..."). This is saga **choreography**: nobody orchestrates steps 8–9, each service reacts to the previous fact. The reserve step (3) stays **orchestrated** because the customer is waiting for that answer.
10. `DELETE /api/v1/orders/{id}` is the customer-initiated compensation: release (idempotent), `CANCELLED`, `OrderCancelled`.
11. Every read and cancel is checked against the order's owner (the JWT `sub`); someone else's order is a 404. A `FAILED` order (outcome unknown) is settled by a background reconciler after 2 minutes: release (idempotent) + `CANCELLED`, so nothing stays in limbo.

## Production practices used (and where)

| Practice | Where |
|---|---|
| DB per service, Flyway migrations, `ddl-auto=validate`, expand/contract-safe | `*/src/main/resources/db/migration` |
| Race-free stock update + idempotent reservations (incl. concurrent retries) | `ProductRepository.decrementStock`, `ReservationService`, `ReservationController` |
| Idempotency-Key for client retries (bound to the caller) | `OrderController`, `OrderService.placeOrder` |
| Object-level authorization (owner or support; 404 for others) | `Caller`, `OrderService` |
| Reconciliation of `FAILED` orders, outbox retention cleanup | `OrderReconciler`, `OutboxRelay.cleanup` |
| Timeouts, retry (transient only), circuit breaker, rate limiter | `InventoryClient`, `resilience4j` in `order-service/.../application.yml` |
| No DB transaction across a remote call; outbox in a `TransactionTemplate` | `OrderService` |
| Transactional outbox as a shared library (topic per row), relay with `SKIP LOCKED`, idempotent producer | `shopflow-outbox/` |
| Saga choreography with compensation (payment declined → stock released) | `payment-service/`, `order-service/.../payment` |
| Architecture rules enforced by the build (ArchUnit) | `order-service/.../architecture/ArchitectureTest` |
| JWT audience pinning, lazy JWKS so the service starts without the IdP | `security/JwtDecoderConfig` |
| Idempotent consumer, dead-letter topic, manual offset commit | `notification-service` |
| OAuth2 resource server, stateless, role-based access, Keycloak ↔ Cognito | `order-service/.../security` |
| Cache-aside with eviction on write, JSON values, fail-open when Redis is down | `ProductController`, `ReservationService`, `CacheConfig` |
| Optimistic locking (`@Version`) → 409 | `Order`, `GlobalExceptionHandler` |
| RFC 7807 errors with field-level validation messages | `GlobalExceptionHandler` |
| Liveness/readiness probes; readiness excludes downstream services | `management.endpoint.health` |
| Graceful shutdown, `preStop`, `maxUnavailable: 0` | `application.yml`, `k8s/base/*/deployment.yaml` |
| Metrics (latency histograms, business counters, breaker, outbox backlog, consumer lag) | Actuator + Micrometer → `/actuator/prometheus` |
| Distributed tracing (OTLP → Tempo), traces ↔ logs ↔ metrics in Grafana | parent `pom.xml`, `monitoring/` |
| Structured JSON logs (ECS) shipped by Alloy to Loki | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `monitoring/alloy.river` |
| Java 21 virtual threads | `spring.threads.virtual.enabled` |
| Tests: full context + WireMock + EmbeddedKafka + fake JWTs; `@WebMvcTest` slice; concurrency, saga and architecture tests | `src/test` (46 tests) |
| Multi-stage, layered, non-root image, one Dockerfile for all services | `Dockerfile` |
| Kustomize base + dev/prod overlays, HPA (no `spec.replicas`), PDB, ingress+egress NetworkPolicies, securityContext | `k8s/` |
| The same manifests as a Helm chart, with a Kustomize-vs-Helm comparison | `helm/` |
| Load test with pass/fail thresholds (exactly 3 units sold to 300 buyers) | `loadtest/flash-sale.js` (k6) |
| Prod on AWS managed services: RDS, MSK, ElastiCache, Cognito, Secrets Manager via External Secrets, ALB | `k8s/overlays/prod/`, `infra/terraform/aws/` |
| CI: test → validate (manifests, Helm, alerts, Terraform, k6) → build → Trivy → SBOM → push → cosign sign → pin SHA into prod overlay | `.github/workflows/shopflow.yml`, `Jenkinsfile` |
| GitOps: Argo CD syncs the prod overlay; CI never runs kubectl | `argocd/` |
| Alerts on error rate, p99, open breaker, down targets, outbox backlog, consumer lag | `monitoring/alert-rules.yml` |

## Run it

### 1. Tests only (needs JDK 21)
```sh
./mvnw verify            # 46 tests: H2 in PostgreSQL mode, embedded Kafka, WireMock, fake JWTs, ArchUnit
```

### 2. Whole stack with Docker Compose
```sh
docker compose up --build -d
docker compose ps        # wait until the three services are "healthy" (Keycloak takes ~30s)

# get a token for alice (role customer) from Keycloak
TOKEN=$(curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shopflow-web -d username=alice -d password=alice | jq -r .access_token)

curl localhost:8082/api/v1/products | jq                    # no token needed (internal API, open locally)
curl -i -XPOST localhost:8081/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"sku":"PS5-SLIM","quantity":1}'
docker compose logs -f notification-service | grep EMAIL    # the event arrived through Kafka
docker compose logs payment-service | grep 'payment for order'  # ... and was charged; order becomes PAID

docker compose stop inventory-service   # watch order-service degrade: 503s, then the circuit opens
docker compose stop kafka               # orders still work; outbox_unpublished grows; alert after 5 min
```
Grafana http://localhost:3000 (admin/admin): Explore → Tempo → search `service.name=order-service`, open a trace, click "Logs for this span". Prometheus http://localhost:9090: try `orders_total`, `outbox_unpublished`, `resilience4j_circuitbreaker_state`.

### 3. Kubernetes (minikube)
```sh
minikube start --cni=calico --memory=6g       # calico = NetworkPolicies are enforced
minikube addons enable ingress metrics-server

for s in order-service inventory-service notification-service payment-service; do
  docker build --build-arg SERVICE=$s -t shopflow/$s:dev . && minikube image load shopflow/$s:dev
done

kubectl apply -k k8s/overlays/dev
kubectl -n shopflow get pods -w

echo "$(minikube ip) shopflow.local keycloak.shopflow.local" | sudo tee -a /etc/hosts
curl http://shopflow.local/api/v1/products
```

Preview prod: `kubectl kustomize k8s/overlays/prod` (AWS endpoints come from `terraform output`).

### 4. AWS
See [`../infra/terraform/aws/README.md`](../infra/terraform/aws/README.md): VPC → EKS → ECR → RDS → ElastiCache → MSK → Cognito → IRSA, then `kubectl apply -f argocd/application-prod.yaml`. **Mind the cost** (~$400–600/month); destroy the same day when learning.

## Learn with it

- [`../docs/EASY-NOTES.md`](../docs/EASY-NOTES.md): every pattern explained simply: the problem, why, how (steps), where it lives here, how it improved the system.
- [`../docs/ROADMAP.md`](../docs/ROADMAP.md): DevOps + Java backend mastery path built around this repo.
- [`../docs/LABS.md`](../docs/LABS.md): hands-on labs. Break things on purpose, debug them, fix them.
- [`../docs/SELF-WALK.md`](../docs/SELF-WALK.md): the review log, every bug found and why it mattered.
- [`../docs/interview-notes/`](../docs/interview-notes/): interview notes for every topic, plus the hard-questions file.
