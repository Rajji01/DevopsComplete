# Mastery Roadmap: DevOps + Java Backend

Goal: be the engineer who can **build a backend service, ship it, run it in production, and debug it at 3 AM**. That combination (backend + DevOps) is what "senior" looks like in most product companies.

> Tip: Sirf padhna mastery nahi hai. Har phase mein code chalao, todo, fix karo, aur interview notes se khud ko explain karo. Jo cheez tum kisi ko samjha nahi sakte, woh abhi aayi nahi hai.

How to use this repo:

| Folder | Use it for |
|---|---|
| `Devops/`, `microservice01/`, `notes.txt` | Basics you already did: Docker, pods, services, volumes, jobs |
| `shopflow/` | The production-style project; you'll read it, run it, break it and extend it |
| `docs/LABS.md` | Hands-on break/fix labs (the most important part) |
| `docs/interview-notes/` | Revision + interview answers for every topic |

Rough timeline: **~16 weeks at 1.5–2 h/day**. Speed doesn't matter, the "Done when" checklists do.

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

## Phase 5: Observability + SRE (week 12)

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

## Phase 6: Cloud + Infrastructure as Code (week 13–14)

Learn: AWS core (VPC, subnets, SG, EC2, ALB, EKS, ECR, RDS, S3, IAM, CloudWatch), Terraform (state, backend, modules, plan/apply), basic Ansible.

Do:
- Terraform: create an ECR repo + S3 bucket with remote state (S3 backend + locking)
- Optional, and watch the cost: EKS cluster via the `terraform-aws-modules/eks` module, deploy ShopFlow with RDS instead of the in-cluster Postgres, then `terraform destroy` the same day

Done when:
- [ ] You can draw ShopFlow's AWS architecture (VPC, public/private subnets, ALB, EKS, RDS)
- [ ] You can explain Terraform state and what happens if two people run `apply` together

Notes: [`08-linux-networking-cloud-iac.md`](interview-notes/08-linux-networking-cloud-iac.md)

---

## Phase 7: Advanced backend (week 15–16+)

Extend ShopFlow yourself. Each item is a strong resume line:

| Feature | What you learn |
|---|---|
| `payment-service` + Kafka: order publishes `OrderPlaced` via **transactional outbox** | async messaging, at-least-once delivery, idempotent consumers |
| Redis cache for `GET /products/{sku}` | caching, TTL, cache invalidation |
| Spring Security + Keycloak (OAuth2/JWT) | authn/authz, resource server |
| Spring Cloud Gateway in front, with rate limiting | API gateway pattern |
| Testcontainers (real Postgres in tests) | realistic integration tests |
| k6 / Gatling load test: 500 parallel buyers for 3 PS5s | performance testing, proving no overselling under load |
| Outbox cleaner / scheduled reconciliation of `FAILED` orders | consistency in distributed systems |

---

## Resume lines (after you've actually done the labs)

- Built order and inventory microservices (Java 21, Spring Boot 3, PostgreSQL) with idempotent APIs and atomic stock reservation, verified race-free by a concurrency test.
- Added resilience: timeouts, retry with backoff, and a Resilience4j circuit breaker. Inventory outages degrade to fast 503s instead of cascading.
- Containerized the services with multi-stage, layered, non-root images, and deployed them to Kubernetes using Kustomize (HPA, PDB, NetworkPolicies, probes, zero-downtime rolling updates).
- Built a GitHub Actions pipeline: tests, manifest validation, Trivy image scanning, and pushes to GHCR. Set up Prometheus alerts on error rate, latency and circuit-breaker state.

> Tip: Interview mein har line pe "why" ka jawab ready rakho. Jaise: "Why circuit breaker?", "Why maxUnavailable 0?", "Why readiness doesn't check inventory?" Yeh sab answers notes mein hain.
