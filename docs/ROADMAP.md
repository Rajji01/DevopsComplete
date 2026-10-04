# Mastery Roadmap: DevOps + Java Backend

Goal: be the engineer who can **build a backend service, ship it, run it in production, and debug it at 3 AM**. That combination (backend + DevOps) is what "senior" looks like in most product companies.

> Tip: Sirf padhna mastery nahi hai. Har phase mein code chalao, todo, fix karo, aur interview notes se khud ko explain karo. Jo cheez tum kisi ko samjha nahi sakte, woh abhi aayi nahi hai.

How to use this repo:

| Folder | Use it for |
|---|---|
| `Devops/`, `microservice01/`, `notes.txt` | Basics you already did: Docker, pods, services, volumes, jobs |
| `shopflow/` | The production-style project (3 services, Kafka, Redis, JWT, tracing); you'll read it, run it, break it and extend it |
| `infra/terraform/aws/` | How prod runs on AWS: EKS, RDS, MSK, ElastiCache, Cognito, IRSA |
| `docs/SELF-WALK.md` | The review log: every bug found in this repo, why it mattered, how it was fixed |
| `docs/EASY-NOTES.md` | Read this first when a concept feels heavy: problem → why → how → where in ShopFlow → how prod improved |
| `docs/LABS.md` | Hands-on break/fix labs (the most important part) |
| `docs/interview-notes/` | Revision + interview answers for every topic |

Rough timeline: **~18 weeks at 1.5–2 h/day**. Speed doesn't matter, the "Done when" checklists do.

---

## Phase 0: Foundations (week 1–2)

Learn:
- Linux: processes, signals (SIGTERM vs SIGKILL), permissions, systemd, `ps/top/ss/lsof/df/free/journalctl`, `grep/awk/sed`, bash scripting
- Networking: TCP handshake, DNS, HTTP/HTTPS/TLS, ports, CIDR, NAT, L4 vs L7 load balancing
- Git: branching, merge vs rebase, revert vs reset, resolving conflicts, PR workflow

Do:
- Write a bash script that health-checks both ShopFlow services (`curl -f .../actuator/health`) and prints which one is down.
- Use `ss -tlnp` to find which process listens on 8081/8082 while the stack runs.

Done when:
- [ ] You can explain "what happens when you type a URL in the browser", end to end
- [ ] You can debug "port already in use" and "permission denied" without Googling

Notes: [`08-linux-networking-cloud-iac.md`](interview-notes/08-linux-networking-cloud-iac.md)

---

## Phase 1: Java + Spring Boot like production (week 3–5)

Read ShopFlow code in this order. For each file, ask yourself *why* it is written this way:
1. `inventory-service/.../ProductRepository.java`: atomic stock update (why not read-then-write?)
2. `inventory-service/.../ReservationService.java`: idempotency, one transaction
3. `order-service/.../OrderService.java`: why no `@Transactional` around the HTTP call
4. `order-service/.../InventoryClient.java` + `application.yml` `resilience4j`: timeouts, retry, circuit breaker
5. `*/common/GlobalExceptionHandler.java`: ProblemDetail, validation errors
6. Tests: `InventoryServiceApplicationTests.concurrentReservationsNeverOversell`, `OrderServiceApplicationTests` (WireMock)

Learn alongside: Java 17/21 features, collections internals, concurrency, JVM memory/GC, Spring DI/beans, `@Transactional` pitfalls, JPA (N+1, locking), REST design, testing.

Do:
- Labs 1–4 in [`LABS.md`](LABS.md)
- Add `GET /api/v1/orders?status=CONFIRMED` filtering yourself, with a test
- Write a `@DataJpaTest` for `ProductRepository.decrementStock`

Done when:
- [ ] You can explain race conditions, idempotency and circuit breakers *using this code*
- [ ] You can write a REST endpoint + validation + error handling + test without copying

Notes: [`01-project-walkthrough.md`](interview-notes/01-project-walkthrough.md), [`02-java-spring-boot.md`](interview-notes/02-java-spring-boot.md), [`03-microservices.md`](interview-notes/03-microservices.md)

---

## Phase 2: Docker (week 6)

Learn: images vs containers, layers and cache, multi-stage builds, non-root users, volumes, networks, Compose, healthchecks, image scanning.

Do:
- Read `shopflow/Dockerfile` line by line. Build it, then run `docker history shopflow/order-service:local` and find the dependency layer vs the application layer.
- Change one line of Java and rebuild. Notice that only the small `application/` layer changes.
- `docker compose up`, then Labs 1, 4 and 5 using Compose.

Done when:
- [ ] You can write a production Dockerfile for any Spring Boot app from memory
- [ ] You can explain why `:latest` is bad in production and what to use instead

Notes: [`04-docker.md`](interview-notes/04-docker.md)

---

## Phase 3: Kubernetes (week 7–9)

First revise your old manifests in `Devops/` (pods, services, ConfigMaps, Secrets, volumes, Jobs, CronJobs), then move to `shopflow/k8s/`.

Learn: architecture, Deployments/StatefulSets, Services and DNS, Ingress, probes, resources/QoS, HPA, PDB, rolling updates, NetworkPolicy, RBAC, securityContext, Kustomize (and Helm).

Do (minikube):
- `kubectl apply -k shopflow/k8s/overlays/dev`
- Labs 5–11 and 15: readiness vs liveness, OOMKilled, CrashLoopBackOff, ImagePullBackOff, rollback, HPA, NetworkPolicy, graceful shutdown
- Write a Helm chart for order-service (compare with Kustomize: which do you prefer, and why?)

Done when:
- [ ] Given any broken pod, you can find the cause with `get / describe / logs / events / exec`
- [ ] You can explain what happens, step by step, when you run `kubectl apply` on a Deployment
- [ ] You can do a zero-downtime deploy and a rollback, and prove there was zero downtime

Notes: [`05-kubernetes.md`](interview-notes/05-kubernetes.md)

---

## Phase 4: CI/CD + GitOps (week 10–11)

Learn: pipeline stages, GitHub Actions (matrix, caching, permissions, secrets, OIDC), Jenkins basics, branching strategies, deployment strategies (rolling / blue-green / canary), GitOps with Argo CD, DevSecOps.

Do:
- Read `.github/workflows/shopflow.yml`, open a PR, and watch every job run
- Lab 14: make CI fail on purpose (test, manifest, CVE), then fix it
- Install Argo CD on minikube and point it at `shopflow/k8s/overlays/dev`. Change replicas in git and watch it sync.
- Add a pipeline step that bumps the image tag in `overlays/prod` after a push to main (GitOps style)

Done when:
- [ ] You can draw your pipeline on a whiteboard and justify every stage
- [ ] You can explain how a commit reaches production and how you would roll back

Notes: [`06-cicd-gitops.md`](interview-notes/06-cicd-gitops.md)

---

## Phase 4b: Events + security + cache (week 11–12)

Learn: Kafka fundamentals, the dual-write problem and the transactional outbox, idempotent consumers, dead-letter topics, OAuth2/OIDC + JWT, cache-aside and eviction, rate limiting, tracing.

Read, in order: `order-service/.../outbox/*`, `OrderService.saveWithEvent`, `notification-service/.../OrderEventListener`, `KafkaErrorConfig`, `order-service/.../security/*`, `ProductController` + `ReservationService` cache annotations.

Do:
- Labs 16–22
- Add a `payment-service` that consumes `OrderConfirmed`, "charges" the card, and emits `PaymentCaptured`/`PaymentFailed`; order-service consumes those and cancels on failure (saga choreography). Keep every consumer idempotent.
- Replace JSON events with Avro + a Schema Registry (Confluent or Apicurio) and break compatibility on purpose to see the registry reject it.

Done when:
- [ ] You can explain outbox vs CDC vs 2PC and why "exactly-once" doesn't cover sending an email
- [ ] You can explain a JWT's lifecycle from login to a 401 without looking anything up
- [ ] You can say when NOT to cache and why ShopFlow evicts instead of updating

Notes: [`10-kafka-event-driven.md`](interview-notes/10-kafka-event-driven.md), [`11-security-caching-performance.md`](interview-notes/11-security-caching-performance.md)

---

## Phase 5: Observability + SRE (week 13)

Learn: logs/metrics/traces, PromQL, Grafana, alerting (symptoms not causes), RED/USE, SLI/SLO/error budgets, incident response.

Do:
- Labs 4 and 13: watch `resilience4j_circuitbreaker_state` change, then write and fire your own alert
- Build a Grafana dashboard for order-service: request rate, error %, p99 latency, orders by status
- Add OpenTelemetry tracing (Micrometer Tracing + OTLP to Jaeger) and follow one order across both services

Done when:
- [ ] You can write the PromQL for error rate and p99 latency from memory
- [ ] You can define an SLO for `POST /api/v1/orders` and say what alert protects it

Notes: [`07-observability-sre.md`](interview-notes/07-observability-sre.md)

---

## Phase 6: AWS + Infrastructure as Code (week 14–15)

Learn: AWS core (VPC, subnets, SG/NACL, EC2, ALB, EKS, ECR, RDS, ElastiCache, MSK, Cognito, Secrets Manager, IAM/IRSA, CloudWatch), Terraform (state, backend, modules, plan/apply, drift), basic Ansible.

Read: `infra/terraform/aws/` file by file with its README, then `k8s/overlays/prod/` and see how every managed endpoint reaches the pods.

Do:
- Lab 23, then `terraform plan` against your own account and read every line of the plan
- Cheap first: a `terraform.tfvars` with `db_multi_az=false`, small instances, destroy the same day. Compare the bill estimate with the README's numbers.
- Install External Secrets Operator + AWS Load Balancer Controller with Helm, annotate their ServiceAccounts with the IRSA role ARNs, and watch a Secret appear from Secrets Manager
- Point Argo CD at `k8s/overlays/prod` and do one GitOps deploy end to end (push to main → CI pins the SHA → Argo syncs)

Done when:
- [ ] You can draw ShopFlow's AWS architecture (VPC, public/private subnets, ALB, EKS, RDS)
- [ ] You can explain Terraform state and what happens if two people run `apply` together

Notes: [`08-linux-networking-cloud-iac.md`](interview-notes/08-linux-networking-cloud-iac.md)

---

## Phase 7: Advanced (week 16+)

Outbox, Kafka consumer, JWT, cache, tracing and rate limiting are already in the repo (read `docs/SELF-WALK.md` for why each exists). Extend it yourself; each item is a strong resume line:

| Feature | What you learn |
|---|---|
| `payment-service` + saga choreography (`PaymentFailed` → order cancelled) | compensations across services, idempotent consumers |
| Reconciliation job for `FAILED` orders + outbox cleaner | consistency in distributed systems, scheduled jobs, `SKIP LOCKED` |
| Avro + Schema Registry for `orders.events` | schema evolution, compatibility modes |
| Testcontainers (real Postgres + Kafka in tests) | tests that catch PostgreSQL-only SQL (partial indexes, `SKIP LOCKED`) |
| Spring Cloud Gateway / AWS API Gateway with per-user rate limits | API gateway pattern, Redis-backed limits |
| k6 load test: 1000 buyers for 3 PS5s, p99 under 300 ms | performance testing, proving no overselling under load |
| MSK IAM auth in the services (`aws-msk-iam-auth`) | SASL, IRSA in a client library |
| Karpenter instead of managed node groups; Spot for stateless pods | cost-aware autoscaling |
| Chaos: kill a Kafka broker / RDS failover during a load test | resilience verification, RPO/RTO |
| Service mesh (Istio/Linkerd) for mTLS between services | zero trust, L7 policy |

---

## Resume lines (after you've actually done the labs)

- Built order and inventory microservices (Java 21, Spring Boot 3, PostgreSQL) with idempotent APIs and atomic stock reservation, verified race-free by a concurrency test.
- Added resilience: timeouts, retry with backoff, and a Resilience4j circuit breaker. Inventory outages degrade to fast 503s instead of cascading.
- Implemented the transactional outbox pattern with Kafka and an idempotent consumer with a dead-letter topic, so order events are never lost or processed twice.
- Secured the API as an OAuth2 resource server (Keycloak locally, Amazon Cognito in prod) with role-based access, and added Redis cache-aside, rate limiting and OpenTelemetry tracing linked to logs and metrics in Grafana.
- Containerized the services with multi-stage, layered, non-root images, and deployed them to Kubernetes using Kustomize (HPA, PDB, ingress+egress NetworkPolicies, probes, zero-downtime rolling updates).
- Wrote Terraform for the AWS production platform (VPC, EKS with IRSA, ECR, Multi-AZ RDS, ElastiCache, MSK, Cognito, Secrets Manager via External Secrets) and a GitOps flow: GitHub Actions builds, scans and pins the image SHA; Argo CD deploys.
- Built the CI pipeline: tests, manifest/alert/Terraform validation, Trivy image scanning, pushes to GHCR; Prometheus alerts on error rate, latency, circuit-breaker rejections, outbox backlog and consumer lag.

> Tip: Interview mein har line pe "why" ka jawab ready rakho. Jaise: "Why circuit breaker?", "Why maxUnavailable 0?", "Why readiness doesn't check inventory?" Yeh sab answers notes mein hain.
