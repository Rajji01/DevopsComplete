# ARCHITECTURE — ShopFlow, ek jagah (C4 + flows + prod)

> **Maqsad:** poora system ek page pe — kaunse services, kaun kisse baat karta (REST/Kafka/JDBC), ek order ka end-to-end flow, aur dev↔prod mapping. Deep diagrams: [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md) · design reasoning: [`HLD-SYSTEM-DESIGN.md`](HLD-SYSTEM-DESIGN.md) · patterns: [`LLD-DESIGN-PATTERNS.md`](LLD-DESIGN-PATTERNS.md) · mechanisms: [`comprehension/`](comprehension/README.md).

ShopFlow = ek e-commerce **checkout** system: order lo → stock reserve karo → pay karo → confirm/cancel/refund. 4 microservices, Kafka saga + transactional outbox, Postgres (per-service DB), Redis cache, Keycloak/Cognito auth.

---

## 1. Context (C4 L1 — system aur duniya)
```
         ┌───────────┐          ┌──────────────────────────────┐          ┌──────────────┐
  user ─►│  customer │ ──JWT──► │        ShopFlow system       │ ◄──OIDC──│ IdP          │
  (app)  │  / support│   REST   │ (order/inventory/payment/    │  tokens  │ Keycloak /   │
         └───────────┘          │  notification + outbox)      │          │ Cognito      │
                                └───────────┬──────────────────┘          └──────────────┘
                                            │ charge/refund
                                            ▼
                                   ┌──────────────────┐
                                   │ Payment gateway  │  (FakePaymentGateway dev; real processor prod)
                                   └──────────────────┘
```
- **customer/support** → order-service ko REST se (Bearer JWT). **IdP** JWT deta (order-service validate karta, OAuth2 resource server).
- **Payment gateway** = `PaymentGateway` Strategy interface (dev: `FakePaymentGateway` limit 100000; prod: real).

---

## 2. Container (C4 L2 — services + stores, kaun kisse baat karta)
```
                         ┌──────────────── ShopFlow (namespace: shopflow) ────────────────┐
                         │                                                                │
   customer ──REST/JWT──►│  order-service  :8081  ──REST (reserve/release)──► inventory-service :8082
                         │      │  │                                               │                │
                         │  JDBC│  │ produce/consume                          JDBC │  cache (@Cacheable)
                         │      ▼  ▼                                               ▼                ▼
                         │  Postgres[orders]   Kafka ◄──────────────────►  Postgres[inventory]   Redis
                         │                      │  orders.events / payments.events                │
                         │        ┌─────────────┼───────────────────────┐                         │
                         │        ▼             ▼                        ▼                         │
                         │  payment-service :8084         notification-service :8083               │
                         │      │ JDBC                         │ JDBC                               │
                         │      ▼                              ▼                                    │
                         │  Postgres[payments]           Postgres[notifications]                   │
                         └────────────────────────────────────────────────────────────────────────┘
       auth: every service validates JWT via Keycloak/Cognito (JWKS).   Observability: Prometheus/Tempo/Loki/Grafana scrape all.
```

**Baat-cheet (edges):**
| Caller | Callee | Protocol | Sync/Async | Kyu |
|---|---|---|---|---|
| customer | order-service | REST (JWT) | sync | order place/get/cancel |
| order-service | inventory-service | REST | **sync** | reserve/release stock (customer ko turant jawab chahiye) — orchestrated |
| order-service | Kafka `orders.events` | produce (outbox) | **async** | OrderConfirmed/Cancelled/RefundPending — choreography |
| payment-service | Kafka `orders.events` | consume | async | OrderConfirmed → charge; OrderRefundPending → refund |
| payment-service | Kafka `payments.events` | produce (outbox) | async | PaymentCaptured/Failed/Refunded/RefundFailed |
| order-service | Kafka `payments.events` | consume | async | → PAID / CANCELLED / REFUNDED / stays-REFUND_PENDING |
| notification-service | Kafka `orders.events` | consume | async | email (idempotent) |
| inventory-service | Redis | cache | sync | product read (cache-aside, fail-open) |
| each service | Postgres (own DB) | JDBC | sync | state (no shared DB — DB-per-service) |

> **Rule:** sirf order→inventory **REST** (sync answer). Baaki sab **Kafka events** (loose coupling). DB **per service** (koi service dusre ki table nahi chhuti) — N+1 ka mauka hi nahi (LLD §14).

---

## 3. Component (C4 L3 — order-service ke andar)
```
  HTTP ─► OrderController (/api/v1/orders, @RateLimiter 50/s/pod, DTOs)
            │ (ArchUnit: controller ↛ repository/@Entity)
            ▼
          OrderService  ──► InventoryClient (@Retry+@CircuitBreaker, 1s/2s timeout) ──REST──► inventory
            │  saveWithEvent (TransactionTemplate: orders row + outbox_event row, 1 txn)
            ▼
          OrderRepository(JPA) / outbox_event table
            ▲                         ▲
            │                         │ @Scheduled 1s, FOR UPDATE SKIP LOCKED
   PaymentEventListener           OutboxRelay ──► Kafka orders.events
   (@KafkaListener payments.events,  (at-least-once; markPublished after ack)
    processed_event dedupe)
            ▲
   OrderReconciler (@Scheduled 60s: FAILED/PENDING→cancel; CONFIRMED stuck→gauge+alert)
```
- Same **OrderService** ko HTTP (`OrderController`) **aur** Kafka (`PaymentEventListener`) dono call karte — isliye service web-agnostic (ArchUnit rule 2).

---

## 4. End-to-end flow (ek order ki zindagi)
```
 1. POST /orders ──► order PENDING (orders DB)
 2. order→inventory reserve (REST, sync)
       ok  ──► CONFIRMED           reject(409/404) ──► REJECTED      unreachable(timeout/breaker) ──► FAILED
 3. saveWithEvent: orders row + outbox_event(OrderConfirmed) ── 1 txn ──► 201 to customer
 4. OutboxRelay (1s) ──► Kafka orders.events (key=orderRef, acks=all)
 5. payment-service consume OrderConfirmed ──► gateway.charge ──► payment row + PaymentCaptured/Failed (1 txn, outbox)
 6. order-service consume PaymentCaptured ──► PAID    | PaymentFailed ──► release stock + CANCELLED
 7. notification-service consume OrderConfirmed ──► email (processed_event dedupe)

 Cancel a PAID order (refund saga):
   DELETE /orders/{id} ──► REFUND_PENDING + OrderRefundPending
     payment-service refund(): CAPTURED? ──► approve: REFUNDED + PaymentRefunded ──► order release stock + REFUNDED
                                           ──► decline: stays CAPTURED + PaymentRefundFailed ──► order stays REFUND_PENDING + alert + retry
     (money pehle wapas, stock baad mein — fail refund stock leak nahi karta)
```
Full state machine + per-step diagrams: [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md) §2–5.

---

## 5. Cross-cutting (har jagah lagta hai)
| Concern | Kaise | Dekho |
|---|---|---|
| **Dual-write** | transactional outbox (order+event 1 txn) + polling relay (at-least-once) | [comp 01](comprehension/01-outbox-dual-write/) |
| **Idempotency** | client Idempotency-Key · consumer `processed_event` (eventId) · payment PK=eventId · refund by-state | [comp 03](comprehension/03-idempotency-three-ways/) |
| **Saga** | choreography (events) + compensation (release/refund); reserve orchestrated (REST) | [comp 02](comprehension/02-saga-cancel-refund/) |
| **Resilience** | timeout 1s/2s + retry(transient) + circuit breaker(50%/10) → FAILED, reconciler settle | [comp 09](comprehension/09-resilience-breaker-retry/) |
| **Overselling** | atomic `UPDATE … WHERE quantity>=:qty` (row lock), stock never from cache | [comp 05](comprehension/05-overselling-concurrency/) |
| **Security** | OAuth2 resource server (JWT: JWKS/RS256/exp/iss/aud), RBAC, IDOR→404 | [comp 10](comprehension/10-oauth2-jwt-idor/) |
| **Observability** | RED + SLO burn-rate alerts + traces↔logs (trace.id, Kafka headers) | [comp 06](comprehension/06-slo-burn-rate-bug/), [13](comprehension/13-tracing-logs-correlation/) |
| **Caching** | cache-aside (DTO), evict-on-write, fail-open (Redis down → DB) | [comp 12](comprehension/12-caching-cache-aside-failopen/) |

---

## 6. Deployment / platform (dev → prod)
```
 DEV (docker-compose / minikube)                PROD (AWS, EKS)
 ────────────────────────────────               ───────────────────────────────────────
 4 services (compose / Kustomize dev)     ──►    EKS pods (Kustomize prod overlay / Helm)
 in-cluster Postgres                      ──►    RDS (Multi-AZ, PITR)
 in-cluster Kafka (KRaft, 3 partitions)   ──►    MSK (RF3, min.insync 2, 6 partitions, IAM auth)
 in-cluster Redis                         ──►    ElastiCache
 Keycloak                                 ──►    Cognito
 secretGenerator literals                 ──►    External Secrets ← Secrets Manager (IRSA)
 Prometheus/Alertmanager/Tempo/Loki/Grafana      same stack (+ ALB ingress)
```
- **Pods → AWS**: no access keys — **IRSA** (OIDC→STS, least-privilege IAM). CI → AWS: GitHub OIDC (`ci-oidc.tf`). [comp 20](comprehension/20-eks-irsa-least-privilege/)
- **Secrets**: dev literals → prod **External Secrets Operator** ← Secrets Manager (no git, rotation, IRSA). [comp 21](comprehension/21-external-secrets/)
- **Auth**: dev Keycloak → prod **Cognito** (auth-code+PKCE, 15m/30d tokens); resource server config-swap, not code. [comp 22](comprehension/22-cognito-prod-auth/)
- **Kafka**: dev single-node → prod **MSK** (acks=all + RF3 + minISR2, IAM auth) — acked = 2 replicas, survive 1 AZ. [comp 23](comprehension/23-msk-kafka-prod/)
- **Network**: **zero-trust NetworkPolicies** (default-deny + allow-list; CNI-enforced) — one RCE can't pivot to the DB. [comp 24](comprehension/24-network-policies-zero-trust/)
- **Deploy**: RollingUpdate (`k8s/base`, `maxUnavailable:0`+`maxSurge:1`+preStop+graceful) · advanced Argo Rollouts canary/blue-green (`k8s/rollouts`). [comp 14](comprehension/14-kustomize-vs-helm/), [15](comprehension/15-canary-bluegreen-rollouts/)
- **Scale/availability**: HPA (CPU 70%, 2–6, pool 5/pod budgeted) + PDB (minAvailable 1). [comp 18](comprehension/18-hpa-pdb-autoscaling/)
- **Supply chain**: Trivy → SBOM → cosign keyless → Kyverno verifyImages (admission). [comp 08](comprehension/08-supply-chain-signing/)
- **Backups**: pg_dump CronJob (dev, ~24h RPO) → RDS snapshots+PITR (prod, ~5min). [comp 19](comprehension/19-backups-rpo-rto/)

---

## 7. Tech stack (ek nazar)
- **Backend:** Java 21 (virtual threads), Spring Boot 3.5 (MVC, Data JPA, Kafka, Security OAuth2, Cache), Resilience4j, Micrometer/OpenTelemetry, ArchUnit.
- **Data:** PostgreSQL (DB-per-service, Flyway), Apache Kafka (KRaft dev / MSK prod), Redis (ElastiCache prod).
- **Platform:** Docker (multi-stage, distroless variant), Kubernetes (Kustomize + Helm + Argo Rollouts + Kyverno/Gatekeeper + HPA/PDB/NetworkPolicy), Terraform (AWS: VPC/EKS/RDS/MSK/ElastiCache/Cognito/ECR/IRSA), Ansible (VM alt).
- **CI/CD:** GitHub Actions (+ Jenkinsfile), Argo CD (GitOps), cosign/SBOM/Trivy.
- **Observability:** Prometheus + Alertmanager, Tempo (traces), Loki (logs), Grafana, k6 (load).
- **Auth:** Keycloak (dev) / Cognito (prod), OAuth2/JWT (RS256).

> Ports: order `8081`, inventory `8082`, notification `8083`, payment `8084`, Keycloak `8180`, Prometheus `9090`, Alertmanager `9093`, Grafana `3000`. Topics: `orders.events`, `payments.events` (+ `.DLT`). DBs: `orders`, `inventory`, `notifications`, `payments`.

---

### Jaldi jump
Big-picture diagram → [VISUAL-FLOWS §1](VISUAL-FLOWS.md) · design trade-offs/numbers → [HLD](HLD-SYSTEM-DESIGN.md) · class patterns → [LLD](LLD-DESIGN-PATTERNS.md) · mechanism deep-dive + test → [comprehension](comprehension/README.md) · debug live → [debugging](debugging/README.md) · current state → [STATE](STATE.md).
</content>
