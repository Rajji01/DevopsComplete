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

Walk 3 findings from the AWS review (fixed in the same session):
- The prod overlay's Kafka placeholder used port 9092, but MSK with IAM auth listens on **9098**; fixed, and the services still need the `aws-msk-iam-auth` client library before they can authenticate.
- No ServiceAccounts for IRSA: the kafka-clients IAM role existed in Terraform but no pod ran as a ServiceAccount carrying it → `service-accounts.yaml` + `serviceAccountName` patches in the prod overlay.
- The `*-ingress` NetworkPolicies allowed only the `ingress-nginx` namespace; on AWS the ALB (target-type `ip`) connects from ENIs inside the VPC → prod patches allow the VPC CIDR on 8081/8082.
- ACM certificate and Route 53 validation were referenced by the Ingress but not created → `dns.tf` (optional, `hosted_zone_id`).
- CI pushed only to GHCR while EKS would pull from ECR → OIDC-assumed role + ECR mirror step, enabled by the `AWS_ROLE_ARN` repository variable.

Walk 3 findings from the notes review (fixed in the same session):
- **Redis cache would have crashed on the first put**: `RedisCacheManager` defaults to JDK serialization and `ProductResponse` is a plain record. Tests passed only because they use the `simple` cache. Fix: JSON values via `RedisCacheManagerBuilderCustomizer` + a round-trip test. Lesson: a config switch that tests never flip (`CACHE_TYPE=redis`) is untested code.
- `KafkaConsumerLagHigh` alerted on a metric name that does not exist (`spring_kafka_listener_records_lag_max`); the real one is Micrometer's `kafka_consumer_fetch_manager_records_lag_max`, now asserted in a notification-service test so the alert's dependency is pinned.
- Still open: the rate limiter is per pod (shared limit needs Redis/gateway), `@EnableMethodSecurity` is on but no `@PreAuthorize` is used yet, cancelling a PAID order would need a refund flow (not modelled).

## Walk 4: security and "limbo" review (Oct 4, 2026)

Scope: re-read the order API as an attacker and as an on-call engineer. Question asked at every endpoint: *who* may call this, and *what state can an order get stuck in?*

| Found | Why it mattered | Fix |
|---|---|---|
| **IDOR**: any `customer` could `GET`/`DELETE` *any* order by id. Authentication was there, authorization was only "has the role" | OWASP API #1 (Broken Object Level Authorization). A customer could cancel other people's orders by counting ids | Orders carry the JWT `sub` (`customer_id`); reads/cancels are filtered by owner (**404**, not 403, so ids don't leak); list is scoped; `support` reads everything. Test `customersOnlySeeTheirOwnOrders` |
| Idempotency-Key was global: another user sending the same key got *your* order replayed to them | Information leak + a way to hijack someone's order flow | Key is checked against the owner → **422 "Idempotency-Key conflict"** |
| `FAILED` orders stayed `FAILED` forever; stock might stay reserved if the request *had* reached inventory | Limbo states need an owner. Every "outcome unknown" needs a reconciliation path | `OrderReconciler`: after a grace period, release (idempotent) + `CANCELLED` + event. Test proves it waits while inventory is down and settles when it's back |
| `outbox_event` grew forever | Tables that only grow eventually become the incident | Nightly cleanup of published rows older than 7 days (`cleanupPublishedBefore`, tested) |
| Redis down ⇒ `GET /products/{sku}` failed | A cache must never be a hard dependency of a read path | `LoggingCacheErrorHandler`: cache errors are logged, request falls through to the DB |
| MSK IAM auth documented but not configured | Prod would have failed to connect to Kafka on day one | `aws-msk-iam-auth` + `application-aws.yml` (SASL_SSL / AWS_MSK_IAM); prod overlay sets `SPRING_PROFILES_ACTIVE=aws` |

Lesson of this walk: **authentication ≠ authorization**, and **every async failure needs a reconciler**. Both are invisible in happy-path tests; you only find them by asking "what if the caller is hostile?" and "what if step 3 of 5 never answers?".

## Walk 5: closing the saga, and guarding the architecture (Oct 4, 2026)

Scope: "an order is CONFIRMED... and then? Who takes the money?" plus "how do we keep the code shaped the way we agreed as the team grows?"

| Added / found | Why | Where |
|---|---|---|
| **payment-service** (saga step 2, choreography) | After reserve, somebody must charge. Nobody *calls* payment-service; it reacts to `OrderConfirmed`. Charge + payment row + outgoing event in one transaction (outbox again) | `payment-service/` |
| order-service reacts to `PaymentCaptured` → **PAID**, `PaymentFailed` → release + CANCELLED | The saga's compensation path for a declined card. Status-guarded, idempotent | `order/payment/PaymentEventListener`, `OrderService.onPayment*` |
| Outbox extracted to **`shopflow-outbox`** module with a `topic` column | Two services needed the same 4 classes; copy-paste is how two outboxes drift apart | `shopflow-outbox/` |
| `@EnableJpaRepositories` on the application class broke `@WebMvcTest` | The slice tried to build an EntityManager. Moved to `JpaConfig` (a regular `@Configuration`, which slices skip) | `*/JpaConfig.java` |
| **ArchUnit** rules as tests | "Controllers don't touch repositories" is only true until someone is in a hurry. Rules in the build cost nothing and never get tired | `order-service/.../ArchitectureTest` |
| `aud` validation (optional), lazy `SupplierJwtDecoder` | A token minted for a *different* API by the same issuer used to pass. Also: the service now starts when Keycloak is down | `security/JwtDecoderConfig` |
| Reconciler also sweeps long-**PENDING** orders; `processed_event` cleanup in both consumers | Same "every limbo state needs an owner, every table needs a retention" rules from Walk 4, applied consistently | `OrderService.reconcileFailedOrders`, `ProcessedEventCleanup` |
| **Helm chart** next to Kustomize | Both are asked in interviews; the honest answer is "both, for different things" (`helm/README.md`) | `shopflow/helm/` |
| **k6** flash-sale test with thresholds (`orders_confirmed == 3`) | "No overselling" was proven by a unit test on H2; k6 proves it against the running stack under load | `shopflow/loadtest/flash-sale.js` |
| **SBOM + cosign** in CI | Supply chain: know what is inside every image, and let the cluster refuse unsigned ones | `.github/workflows/shopflow.yml` |
| Payment failure-rate alert | Declines are normal; *most* payments failing means the gateway or pricing broke | `monitoring/alert-rules.yml` |

Walk 5 lessons:
- **Choreography vs orchestration is not either/or**: ShopFlow orchestrates the synchronous part (reserve, because the customer is waiting for the answer) and choreographs the asynchronous part (payment, notification). Say that in interviews; it is the real-world answer.
- A record's static factory cannot share a name with a component (`approved()` vs field `approved`): the compiler told me, not a reviewer. Build errors are the cheapest reviews.
- Test assertions on a **shared MeterRegistry** must compare deltas; absolute counts break the moment a second test runs.
- Running several shell edits in parallel with a shared working directory corrupted nothing but failed all of them: use absolute paths, or run sequentially.

### Honest status (what is NOT verified)

- Docker images, the compose stack and the minikube deploy have **not been run** in this environment (no Docker daemon). CI builds the images; the compose/minikube labs are the user's job.
- The Helm chart was written but `helm lint`/`helm template` could not run here (download blocked); CI runs both and validates the rendered manifests with kubeconform.
- The k6 script was syntax-checked only (CI runs `k6 inspect`); it has not been run against a live stack.
- cosign signing and the SBOM step run only on `main` in GitHub Actions; neither has executed yet.
- Terraform was `fmt`-checked only. `terraform init/validate` runs in CI (the registry was unreachable here) and **nothing has been applied** to a real AWS account; module argument names follow terraform-aws-modules v6 (vpc) / v21 (eks) / v5 (iam) and may need small adjustments on first `plan`.
- MSK IAM authentication is configured (`application-aws.yml`) but has never been exercised against a real MSK cluster.
- Keycloak image tag `26.3` could not be checked against quay.io from here.

### Known gaps to walk next

- Schema evolution for events is "ignore unknown fields" only; a Schema Registry (Avro/Protobuf) is the production answer.
- No Testcontainers: tests run on H2 and an embedded Kafka, so PostgreSQL-only SQL (partial indexes, `SKIP LOCKED` semantics) is not exercised.
- Secrets in the dev overlay are literals in git (fine for minikube, never for anything shared).
