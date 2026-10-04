# Self-walk log: reviewing my own project like a senior engineer

A *self-walk* is walking through your own project the way a reviewer or interviewer would: read every file, ask "why is this here, what breaks it, what would production do differently", and then **fix what you find**. This log records the walks done on this repo, what they found, and what changed. Read it before an interview: these findings are exactly the follow-up questions you will get, and every one has a story now.

> Tip: Khud ka code todna sabse sasta learning hai. Jo bug tum khud pakadte ho, woh interview mein "war story" ban jaata hai.

## How to do your own self-walk (checklist)

1. **Run everything first.** Tests, the compose stack, one real request flow. A walk over code that doesn't run is fiction.
2. **Read in request order**, not file order: entry point → controller → service → repository → DB → downstream call. Write down every assumption the code makes.
3. **Ask the five production questions** at every step: What if this is slow? What if this fails halfway? What if two of these run at once? What if this is called twice? What does an attacker do here?
4. **Check the boring files**: Dockerfile, manifests, CI, alert rules. Most production incidents hide there, not in Java.
5. **Verify claims against reality.** A comment that says "atomic" is a claim. Prove it with a test or a query plan.
6. **Fix or write it down.** Every finding becomes either a commit or an honest line in the notes ("known gap: ..."). Never a silent TODO.
7. **Re-run everything.** Then re-read the diff adversarially: what would make CI reject this?

---

## Walk 1: the first ShopFlow build (Oct 1, 2026)

Scope: the original two services, learning manifests in `Devops/` and `microservice01/`.

| Found | Why it mattered | Fix |
|---|---|---|
| Tests were commented out; `${TESTING_PROPERTY}` had no default so the app could not start outside Docker | A service you cannot run locally never gets tested | Property defaults, MockMvc tests restored |
| Helper service ClusterIP hard-coded in Java (`10.100.13.14`) | IPs change on every redeploy; config belongs outside the code | Env var with service-DNS default |
| `/api/m4` returned `"null host port is …"` | Sloppy string building reaches users | Fixed |
| Downstream call had no timeout, returned `null` on failure | A hung dependency hangs every request thread → cascading failure | 2s/5s timeouts, 503 with a problem body |
| `containerPort: 80` on a service listening on 8081 | Probes and Services target the wrong port | Corrected |
| emptyDir demo ran `sleep` instead of the app | The endpoint it was meant to demonstrate could never work | `exec java -jar` after writing the file |
| `openjdk:17-jdk-alpine` base image (never a real GA tag), root user, JAR copied from host | Unreproducible, insecure image | Multi-stage build on Temurin, non-root |
| 128Mi memory limit for Spring Boot | Guaranteed OOMKilled | 256Mi request / 512Mi limit + probes |
| No CI at all | Nothing stops a broken push | GitHub Actions: tests, docker build, kubeconform |

## Walk 2: production review of ShopFlow (Oct 4, 2026)

Scope: `shopflow/` after the first production-style version. Two independent review passes over code and notes, then fixes.

| Found | Why it mattered | Fix |
|---|---|---|
| Optimistic-lock conflict (`@Version`) surfaced as **500** | A client double-clicking "cancel" saw a server error for a normal race | `OptimisticLockingFailureException` → 409 "Concurrent modification"; `@WebMvcTest` slice proves it |
| Two retries of the same `orderRef` racing in inventory: loser got 409, and order-service mapped every 409 to "insufficient stock" → order **REJECTED although stock was reserved** | Status codes must mean exactly one thing per endpoint; a retry must never change the outcome | Loser now returns the winner's reservation (201); 8-thread test, stock moves once |
| Deployments set `spec.replicas` while an HPA also managed it | Every `kubectl apply`/GitOps sync reset the count → scale-down on each deploy | Removed `replicas`; HPA `minReplicas` is the floor |
| Dev overlay: 1 replica + PDB `minAvailable: 1` | `kubectl drain` can never evict the pod | Dev patches PDB to `maxUnavailable: 1` |
| NetworkPolicies restricted ingress only | A compromised pod could call anything, including the internet | Default-deny egress + DNS, explicit egress per service |
| `DB_POOL_SIZE` 10 × HPA max 10 pods × 2 services = 200 > Postgres `max_connections` 100 | Autoscaling would have taken the database down at peak | Pool 5 per pod, `max_connections=200` (300 on RDS) |
| `InventoryCircuitOpen` alert on the `state="open"` gauge with `for: 1m` | The breaker cycles open → half_open every 10s, so the alert could never fire | Alert on `rate(not_permitted_calls_total) > 0 for 2m` (metric name verified in the Resilience4j jar) |
| Prod overlay used `:latest` | Not traceable, not rollback-able | CI `deploy-manifests` job pins the git SHA into the overlay |
| Validation docs named the wrong exception (`MethodArgumentNotValidException`) | With `@Size` on a header parameter, Spring 6.1+ throws `HandlerMethodValidationException` for the whole method | Notes corrected; both handlers exist |
| Notes' kubectl/Terraform snippets that would fail if pasted | Wrong commands in revision material teach wrong habits | Every snippet re-checked |

## Walk 3: adding the "real production" layer (Oct 4, 2026)

Scope: what a senior would ask next: "how do other services learn about an order?", "who can call this?", "how do you run it on AWS?". Added, then walked:

| Added | Self-walk question it answers | Where |
|---|---|---|
| Transactional outbox + Kafka relay | "You save the order and then call Kafka. What if Kafka is down between the two?" (dual-write problem) | `order-service/.../outbox`, `V2__outbox.sql` |
| `notification-service` idempotent consumer + DLT | "Kafka is at-least-once. How do you avoid two emails? What about a poison message?" | `notification-service/` |
| JWT resource server (Keycloak / Cognito) | "Who is allowed to call POST /orders?" | `order-service/.../security` |
| Redis cache-aside with eviction on every stock change | "Products are read 1000× more than written. Why hit Postgres every time? And how do you avoid serving stale stock?" | `inventory-service` `@Cacheable` / `@CacheEvict` |
| Rate limiter → 429 | "What protects the DB from a flash-sale burst before the HPA reacts?" | `OrderController.create` |
| OpenTelemetry tracing, Tempo/Loki/Alloy, traces ↔ logs | "An order is slow. Which of the three services is the problem?" | parent `pom.xml`, `monitoring/` |
| Virtual threads | "Blocking IO everywhere. Why not reactive?" (Java 21 answer) | `spring.threads.virtual.enabled` |
| Terraform for AWS (VPC, EKS, ECR, RDS, ElastiCache, MSK, Cognito, IRSA, GitHub OIDC) | "Where does prod run, and how does a pod get AWS credentials without keys?" | `infra/terraform/aws/` |
| Prod overlay on managed services, External Secrets, ALB | "Why run Postgres/Kafka in the cluster in prod?" | `k8s/overlays/prod/` |
| Jenkinsfile, Argo CD Applications | "We use Jenkins / Argo here, not GitHub Actions." | `shopflow/Jenkinsfile`, `shopflow/argocd/` |

Walk 3 findings while building (fixed before commit):
- `TransactionTemplate` instead of a `@Transactional` helper: calling an annotated method from the same class bypasses the proxy and would silently run **without** a transaction, defeating the outbox.
- A PostgreSQL partial index (`... where published_at is null`) does not parse in H2 → portable composite index instead. Lesson: test DB ≠ prod DB; Testcontainers is the real fix (on the roadmap).
- Kustomize refused a ConfigMap file outside its directory (`security; file ... is not in or below`) → the Keycloak realm now lives under `k8s/base/keycloak/` and compose mounts it from there.
- `KafkaTestUtils.consumerProps` changed signature in recent spring-kafka → compile error caught by the build, not by me.
- Kafka's `KAFKA_ADVERTISED_LISTENERS` must be a name clients can resolve (`kafka:9092`), otherwise producers connect and then fail on metadata. Classic.

### Honest status (what is NOT verified)

- Docker images, the compose stack and the minikube deploy have **not been run** in this environment (no Docker daemon). CI builds the images; the compose/minikube labs are the user's job.
- Terraform was `fmt`-checked only. `terraform init/validate` runs in CI (the registry was unreachable here) and **nothing has been applied** to a real AWS account; module argument names follow terraform-aws-modules v6 (vpc) / v21 (eks) / v5 (iam) and may need small adjustments on first `plan`.
- MSK IAM authentication needs the `aws-msk-iam-auth` client library and SASL properties that the services do not include yet (documented in `infra/terraform/aws/README.md`).
- Keycloak image tag `26.3` could not be checked against quay.io from here.

### Known gaps to walk next

- `FAILED` orders (inventory unreachable) may leave stock reserved if the request did reach inventory. A reconciliation job that releases/cancels `FAILED` orders older than N minutes is the fix (release is idempotent).
- The outbox table grows forever: add a cleaner for published rows older than 7 days.
- Schema evolution for events is "ignore unknown fields" only; a Schema Registry (Avro/Protobuf) is the production answer.
- No Testcontainers: tests run on H2 and an embedded Kafka, so PostgreSQL-only SQL (partial indexes, `SKIP LOCKED` semantics) is not exercised.
- Secrets in the dev overlay are literals in git (fine for minikube, never for anything shared).
