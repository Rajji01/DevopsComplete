# ShopFlow — production-style microservices

A small e-commerce backend built the way real teams build services: Java 21 + Spring Boot 3.5, PostgreSQL, Kafka, Redis, OAuth2/JWT, Docker, Kubernetes, Terraform on AWS, CI/CD and full observability.

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
               Kafka topic orders.events ──▶ notification-service :8083 ──▶ Postgres: notifications
                                             (idempotent consumer, DLT)      (processed_event)

   Prometheus (metrics) · Tempo (traces) · Loki (logs) · Grafana — one trace id links all three
```

## Services

| | order-service | inventory-service | notification-service |
|---|---|---|---|
| Port | 8081 | 8082 | 8083 |
| Owns | `orders` DB, `outbox_event` | `inventory` DB (products, reservations), Redis cache | `notifications` DB (`processed_event`) |
| API | `POST /api/v1/orders` (JWT role `customer`, optional `Idempotency-Key`)<br>`GET /api/v1/orders?page=&size=` (`customer`/`support`)<br>`GET /api/v1/orders/{id}`<br>`DELETE /api/v1/orders/{id}` (cancel) | `GET /api/v1/products`<br>`GET /api/v1/products/{sku}` (cached)<br>`POST /api/v1/reservations`<br>`DELETE /api/v1/reservations/{orderRef}` | none (consumes `orders.events`) |
| Docs | http://localhost:8081/swagger-ui.html | http://localhost:8082/swagger-ui.html | — |

### Placing an order, end to end

1. The client sends `POST /api/v1/orders` with a JWT. order-service validates signature/expiry/issuer against the issuer's JWKS and checks the `customer` role. Over 50 req/s per pod → `429`.
2. If the `Idempotency-Key` header was seen before, the stored order is returned (`200`). Otherwise the order is saved as `PENDING`.
3. order-service calls `POST /api/v1/reservations` on inventory-service: 1s/2s timeouts, up to 3 attempts with exponential backoff **only** for transient errors, behind a circuit breaker that opens at 50% failures.
4. inventory-service runs `UPDATE product SET quantity = quantity - n WHERE sku = ? AND quantity >= n` and inserts a reservation keyed by `orderRef`, in one transaction. Two buyers can never get the last unit; a retried request gets the existing reservation; two *concurrent* retries both get the winner's reservation. The product's cache entry is evicted.
5. The order becomes `CONFIRMED` (201), `REJECTED` (409/404 from inventory, never retried) or `FAILED` + HTTP 503 (inventory unreachable). **Order row and an `OrderConfirmed`/`OrderRejected`/… event are written in the same DB transaction** (transactional outbox).
6. Every second the outbox relay publishes unpublished rows to Kafka (`acks=all`, idempotent producer, key = `orderRef` so one order's events stay in order) and marks them published. At-least-once: a crash between send and mark re-sends.
7. notification-service consumes the event, checks `processed_event` (duplicate → skipped, counted), "sends the email" (a log line standing in for SES/FCM), and records the event id, all in one transaction. A message that keeps failing is retried with backoff, then moved to `orders.events.DLT`.
8. `DELETE /api/v1/orders/{id}` is the saga's compensation: release the reservation (idempotent), mark `CANCELLED`, emit `OrderCancelled`.

## Production practices used (and where)

| Practice | Where |
|---|---|
| DB per service, Flyway migrations, `ddl-auto=validate`, expand/contract-safe | `*/src/main/resources/db/migration` |
| Race-free stock update + idempotent reservations (incl. concurrent retries) | `ProductRepository.decrementStock`, `ReservationService`, `ReservationController` |
| Idempotency-Key for client retries | `OrderController`, `OrderService.placeOrder` |
| Timeouts, retry (transient only), circuit breaker, rate limiter | `InventoryClient`, `resilience4j` in `order-service/.../application.yml` |
| No DB transaction across a remote call; outbox in a `TransactionTemplate` | `OrderService` |
| Transactional outbox, polling relay with `SKIP LOCKED`, Kafka idempotent producer | `order-service/.../outbox` |
| Idempotent consumer, dead-letter topic, manual offset commit | `notification-service` |
| OAuth2 resource server, stateless, role-based access, Keycloak ↔ Cognito | `order-service/.../security` |
| Cache-aside with eviction on write, Redis in prod, TTL safety net | `ProductController`, `ReservationService` |
| Optimistic locking (`@Version`) → 409 | `Order`, `GlobalExceptionHandler` |
| RFC 7807 errors with field-level validation messages | `GlobalExceptionHandler` |
| Liveness/readiness probes; readiness excludes downstream services | `management.endpoint.health` |
| Graceful shutdown, `preStop`, `maxUnavailable: 0` | `application.yml`, `k8s/base/*/deployment.yaml` |
| Metrics (latency histograms, business counters, breaker, outbox backlog, consumer lag) | Actuator + Micrometer → `/actuator/prometheus` |
| Distributed tracing (OTLP → Tempo), traces ↔ logs ↔ metrics in Grafana | parent `pom.xml`, `monitoring/` |
| Structured JSON logs (ECS) shipped by Alloy to Loki | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `monitoring/alloy.river` |
| Java 21 virtual threads | `spring.threads.virtual.enabled` |
| Tests: full context + WireMock + EmbeddedKafka + fake JWTs; `@WebMvcTest` slice; concurrency tests | `src/test` (26 tests) |
| Multi-stage, layered, non-root image, one Dockerfile for all services | `Dockerfile` |
| Kustomize base + dev/prod overlays, HPA (no `spec.replicas`), PDB, ingress+egress NetworkPolicies, securityContext | `k8s/` |
| Prod on AWS managed services: RDS, MSK, ElastiCache, Cognito, Secrets Manager via External Secrets, ALB | `k8s/overlays/prod/`, `infra/terraform/aws/` |
| CI: test → validate manifests + alert rules + Terraform → build → Trivy → push → pin SHA into prod overlay | `.github/workflows/shopflow.yml`, `Jenkinsfile` |
| GitOps: Argo CD syncs the prod overlay; CI never runs kubectl | `argocd/` |
| Alerts on error rate, p99, open breaker, down targets, outbox backlog, consumer lag | `monitoring/alert-rules.yml` |

## Run it

### 1. Tests only (needs JDK 21)
```sh
./mvnw verify            # 26 tests: H2 in PostgreSQL mode, embedded Kafka, WireMock, fake JWTs
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

docker compose stop inventory-service   # watch order-service degrade: 503s, then the circuit opens
docker compose stop kafka               # orders still work; outbox_unpublished grows; alert after 5 min
```
Grafana http://localhost:3000 (admin/admin): Explore → Tempo → search `service.name=order-service`, open a trace, click "Logs for this span". Prometheus http://localhost:9090: try `orders_total`, `outbox_unpublished`, `resilience4j_circuitbreaker_state`.

### 3. Kubernetes (minikube)
```sh
minikube start --cni=calico --memory=6g       # calico = NetworkPolicies are enforced
minikube addons enable ingress metrics-server

for s in order-service inventory-service notification-service; do
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
