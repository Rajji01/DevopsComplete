# ShopFlow — production-style microservices

A small e-commerce backend built the way real teams build services: Java 21 + Spring Boot 3.5, PostgreSQL, Docker, Kubernetes, CI/CD and monitoring.

Problem it solves: **many customers buy the same limited-stock product at the same time (flash sale). Never oversell, never charge twice, and stay up when a dependency is down.**

```
                    ┌──────────────── Kubernetes namespace: shopflow ────────────────┐
                    │                                                                 │
 client ──HTTPS──▶ Ingress ──/api/v1/orders──▶ order-service ──REST──▶ inventory-service
                    │   │                      (x2-10 pods, HPA)  retry +  (x2-10 pods)  │
                    │   └──/api/v1/products───────────────────── circuit ──▶     │      │
                    │                               │            breaker         │      │
                    │                               ▼                            ▼      │
                    │                        postgres: orders DB      postgres: inventory DB
                    └─────────────────────────────────────────────────────────────────┘
                         Prometheus scrapes /actuator/prometheus  →  Grafana + alerts
```

## Services

| | order-service | inventory-service |
|---|---|---|
| Port | 8081 | 8082 |
| Owns | `orders` DB | `inventory` DB (products, reservations) |
| API | `POST /api/v1/orders` (optional `Idempotency-Key` header)<br>`GET /api/v1/orders?page=&size=`<br>`GET /api/v1/orders/{id}`<br>`DELETE /api/v1/orders/{id}` (cancel) | `GET /api/v1/products`<br>`GET /api/v1/products/{sku}`<br>`POST /api/v1/reservations`<br>`DELETE /api/v1/reservations/{orderRef}` |
| Docs | http://localhost:8081/swagger-ui.html | http://localhost:8082/swagger-ui.html |

### Placing an order

1. order-service saves the order as `PENDING`, with a random `orderRef`.
2. It calls `POST /api/v1/reservations` on inventory-service. That call has timeouts, retries with exponential backoff, and a circuit breaker.
3. inventory-service runs `UPDATE product SET quantity = quantity - n WHERE sku = ? AND quantity >= n` and inserts a reservation row, both in one transaction. Because the check and the decrement are one atomic statement, two buyers can never get the last unit. The reservation is keyed by `orderRef`, so a retried request returns the existing reservation instead of reserving twice.
4. The order ends up in one of these states:
   - `CONFIRMED`: stock was reserved.
   - `REJECTED`: inventory answered 409 (out of stock) or 404 (unknown SKU). These answers are never retried.
   - `FAILED` + HTTP 503: inventory-service was unreachable.
5. `DELETE /api/v1/orders/{id}` cancels the order. It is the compensating step of the saga: it releases the reservation, which is also idempotent.

## Production practices used (and where)

| Practice | Where |
|---|---|
| DB per service, schema migrations (Flyway), `ddl-auto=validate` | `*/src/main/resources/db/migration`, `application.yml` |
| Race-free stock update + idempotent reservations | `ProductRepository.decrementStock`, `ReservationService` |
| Idempotency-Key for client retries | `OrderController`, `OrderService.placeOrder` |
| Timeouts, retry (only transient errors), circuit breaker | `InventoryClient`, `resilience4j` in `order-service/.../application.yml` |
| No DB transaction held across a remote call | `OrderService` |
| Optimistic locking (`@Version`) | `Order` |
| RFC 7807 errors with field-level validation messages | `GlobalExceptionHandler` |
| Liveness/readiness probes; readiness excludes downstream services | `management.endpoint.health` in `application.yml` |
| Graceful shutdown | `server.shutdown=graceful` + K8s `preStop` |
| Metrics (HTTP latency histograms, business counters, breaker state) | Actuator + Micrometer → `/actuator/prometheus` |
| Structured JSON logs in K8s | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` |
| Tests: full context + real HTTP to a fake downstream (WireMock), concurrency test | `src/test` |
| Multi-stage, layered, non-root image | `Dockerfile` |
| Kustomize base + dev/prod overlays, HPA, PDB, NetworkPolicy, securityContext | `k8s/` |
| CI: test → validate manifests → build → Trivy scan → push (main) | `.github/workflows/shopflow.yml` |
| Alerts on error rate, p99 latency, open breaker, down targets | `monitoring/alert-rules.yml` |

## Run it

### 1. Tests only (needs JDK 21)
```sh
./mvnw verify
```

### 2. Whole stack with Docker Compose
```sh
docker compose up --build -d
docker compose ps                     # wait until both services are "healthy"

curl localhost:8082/api/v1/products
curl -i -XPOST localhost:8081/api/v1/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"sku":"PS5-SLIM","quantity":1}'

docker compose stop inventory-service   # watch order-service degrade: 503s, then the circuit opens
```
Prometheus: http://localhost:9090 (try `orders_total` and `resilience4j_circuitbreaker_state`). Grafana: http://localhost:3000 (admin/admin; the Prometheus datasource is already set up).

### 3. Kubernetes (minikube)
```sh
minikube start --cni=calico            # calico = NetworkPolicies are enforced
minikube addons enable ingress
minikube addons enable metrics-server  # needed by the HPA

docker build --build-arg SERVICE=order-service     -t shopflow/order-service:dev .
docker build --build-arg SERVICE=inventory-service -t shopflow/inventory-service:dev .
minikube image load shopflow/order-service:dev
minikube image load shopflow/inventory-service:dev

kubectl apply -k k8s/overlays/dev
kubectl -n shopflow get pods -w

echo "$(minikube ip) shopflow.local" | sudo tee -a /etc/hosts
curl http://shopflow.local/api/v1/products
```

Preview what will be applied: `kubectl kustomize k8s/overlays/prod`.

## Learn with it

- [`../docs/ROADMAP.md`](../docs/ROADMAP.md): DevOps + Java backend mastery path built around this repo.
- [`../docs/LABS.md`](../docs/LABS.md): hands-on labs. Break things on purpose, debug them, fix them.
- [`../docs/interview-notes/`](../docs/interview-notes/): interview notes for every topic.
