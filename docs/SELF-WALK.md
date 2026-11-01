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
- Still open: the rate limiter is per pod (shared limit needs Redis/gateway); `@EnableMethodSecurity` is on but no `@PreAuthorize` is used yet; the refund gateway is faked (always succeeds, so no `RefundFailed` path); genuinely orphaned CONFIRMED orders are alert-only (no orchestrated timeout). See `docs/STATE.md` §4.

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

## Walk 6: DevOps end to end, depth (Oct 17, 2026)

Scope: stop adding features for a moment and ask the platform questions. *Who gets paged, and what do they do? What stops a bad manifest? How is a release rolled out and rolled back? Where is the backup, has anyone restored it? What does a team without Kubernetes do?* Rule for this walk: **every advanced approach is added next to the basic one, never instead of it**, with a comparison (see `docs/STATE.md` §2).

| Found / added | Why it mattered | Where |
|---|---|---|
| Baseline lint of the whole repo: 25 YAML findings (legacy manifests), 7 gitleaks hits (all `TOKEN=$(token …)` examples), Dockerfile infos | A repo that was never linted has a floor of small rot; the secret scanner needs an allowlist or it cries wolf | `.yamllint.yaml`, `.hadolint.yaml`, `.gitleaks.toml`, `.pre-commit-config.yaml`, `make lint` |
| Alerts had no routing: they only existed on the Prometheus page | An alert nobody receives is a dashboard | `monitoring/alertmanager.yml` (page → PagerDuty + Slack, ticket → Slack, inhibition), Alertmanager in compose |
| Alerts had no tests | The `InventoryCircuitOpen` bug from Walk 2 would have been caught by a unit test | `monitoring/tests/alerts_test.yml`, `promtool test rules` in `make validate` and CI |
| No SLO, so "is this bad enough?" was opinion | SLI/SLO + multi-window burn-rate alerts turn it into arithmetic (14.4× over 1h = page) | `monitoring/recording-rules.yml`, `slo-rules.yml` |
| Alerts had no runbooks | 3 AM is not the time to think | `docs/runbooks/*` + `runbook:` annotation on every alert |
| Only RollingUpdate | A bad release reached 100% of pods in a minute | Argo Rollouts canary (Prometheus-gated) and blue-green next to the Deployments |
| Nothing enforced the rules the manifests follow | `:latest`, missing probes, root containers would pass review eventually | Kyverno policies (+ Gatekeeper equivalent), tested offline with `kyverno apply` on the prod overlay and a bad pod |
| No backup for the in-cluster Postgres | A dev DB people rely on with no restore path | nightly `pg_dump` CronJob + restore runbook; prod = RDS PITR |
| No answer for "we don't have Kubernetes" | Many teams run Compose on VMs | Ansible playbook + systemd unit, lint-clean |
| One Dockerfile, shell inside | Shell is attack surface | `Dockerfile.distroless` next to it, trade-offs in the header |
| Terraform had one set of values | dev costs prod money | `environments/dev.tfvars` (single-AZ, small) and `prod.tfvars` |
| 20 ways to run things, none written down | Every approach needs an entry point | `Makefile` (`make help`) and `docs/STATE.md` |

Walk 6 lessons:
- **Validation can be deep without a cluster**: promtool unit tests, amtool, kubeconform with CRD schemas, `kyverno apply`, ansible-lint, tflint. "I can't run it" is rarely "I can't check it".
- A new annotation on an alert (`runbook:`) broke the alert unit tests: the tests pin the exact annotations. Good: that is what they are for.
- yamllint on Helm templates is noise (they are Go templates), on everything else it found real rot.
- Keeping the basic approach next to the advanced one costs a README table and saves the next learner from thinking the advanced one is the only one.

## Walk 7: refund saga, and a limbo that must NOT auto-resolve (Oct 18, 2026)

Scope: the two oldest open items. Both are about money and about the saga's unhappy paths.

| Added | Why it mattered | Where |
|---|---|---|
| Refund flow: `PAID` cancel → `REFUND_PENDING` → payment-service refunds → `PaymentRefunded` → `REFUNDED` + stock released | A paid order could not be cancelled at all. A refund is not a cancel: money has to come back **first**, then stock, so it is another saga leg, not a synchronous delete | `OrderService.cancel/onPaymentRefunded`, `payment-service` refund branch |
| Refund idempotency **by state, not eventId** | The payment row already exists (keyed by the OrderConfirmed event). A redelivered `OrderRefundPending` finds the payment already `REFUNDED` and does nothing — no new key needed | `OrderConfirmedListener.refund` |
| `OrdersStuckConfirmed` gauge + alert **instead of** auto-cancelling CONFIRMED | This is the walk's real lesson. FAILED/PENDING = no money moved = reconciler cancels them safely. CONFIRMED = payment may be in flight, so auto-cancel could strand a paid order. The honest fix is to **not guess**: count them and page a human | `OrderReconciler`, `OrdersStuckConfirmed` alert + runbook |

Walk 7 lessons:
- **"Every limbo needs an owner" (Walk 4) does not mean "every limbo gets auto-cancelled."** The owner of a CONFIRMED-stuck order is a human with a runbook, because the safe action depends on information the order row does not have (did payment-service charge the card?).
- A refund reuses the saga machinery but reverses it: same outbox, same idempotent-consumer pattern, opposite direction. Recognising that kept it to ~40 lines instead of a new subsystem.
- The event **type** is a contract: deriving it from the enum name silently produced `OrderRefund_pending` until the `pascal()` fix. A consumer `switch` on a string is only as good as the string.

### Honest status (what is NOT verified)

- Docker images, the compose stack and the minikube deploy have **not been run** in this environment (no Docker daemon). CI builds the images; the compose/minikube labs are the user's job.
- The Helm chart was written but `helm lint`/`helm template` could not run here (download blocked); CI runs both and validates the rendered manifests with kubeconform.
- Argo Rollouts, Kyverno (incl. signature verification), Alertmanager routing to real PagerDuty/Slack, the backup CronJob and the Ansible playbook are schema/lint-validated here, not executed against a live cluster/VM. `docs/STATE.md` §3 is the authoritative list.
- The k6 script was syntax-checked only (CI runs `k6 inspect`); it has not been run against a live stack.
- cosign signing and the SBOM step run only on `main` in GitHub Actions; neither has executed yet.
- Terraform was `fmt`-checked only. `terraform init/validate` runs in CI (the registry was unreachable here) and **nothing has been applied** to a real AWS account; module argument names follow terraform-aws-modules v6 (vpc) / v21 (eks) / v5 (iam) and may need small adjustments on first `plan`.
- MSK IAM authentication is configured (`application-aws.yml`) but has never been exercised against a real MSK cluster.
- Keycloak image tag `26.3` could not be checked against quay.io from here.

### Known gaps to walk next

- Schema evolution for events is "ignore unknown fields" only; a Schema Registry (Avro/Protobuf) is the production answer.
- No Testcontainers: tests run on H2 and an embedded Kafka, so PostgreSQL-only SQL (partial indexes, `SKIP LOCKED` semantics) is not exercised.
- Secrets in the dev overlay are literals in git (fine for minikube, never for anything shared).

## Walk 8: the alert that could never fire, and the build that could never start (Oct 20, 2026)

Scope: re-read the observability and build plumbing adversarially — "does this alert actually have data in production?" and "does a clean checkout even build?". Two findings, both the "green that is actually red" kind.

| Found | Why it mattered | Fix |
|---|---|---|
| **The checkout SLO burn-rate alerts can never fire in production.** `sli:checkout_bad` subtracts `http_server_requests_seconds_bucket{le="1.0"}`, but `percentiles-histogram: true` does **not** guarantee a bucket at exactly `le=1.0`. If it is absent, PromQL evaluates `5xx + (total − <empty>)` to an **empty vector** → `sli:checkout_error_ratio` is no-data → `CheckoutSLOBurnRateFast/Slow` stay silent through any outage. The alert **unit test injects the `le=1.0` series itself**, so `promtool test rules` is green while prod is blind | The SLO is the headline deliverable of Walk 6; an SLO you cannot alert on is a slide, not an SLO | Declared an explicit 1s SLO boundary (`management.metrics.distribution.slo.http.server.requests: 1s`) in order-service, which guarantees the `le=1.0` bucket. New test `httpServerRequestsHistogramHasTheOneSecondSloBucket` pins it against the **real** Micrometer config (the rules test can't) |
| **`./mvnw` 404s on a clean checkout**, so `make test` and the CI `test` job (`./mvnw -B verify`) cannot even start. `shopflow/.mvn/wrapper/maven-wrapper.properties` had a botched version bump: `.../apache-maven/3.9.11/apache-maven-3.9.2-bin.zip` — directory `3.9.11`, filename `3.9.2`, a path that does not exist on Maven Central | A build that only works because of a stale cached distribution on one laptop is a time bomb for CI and every new contributor | Corrected to a consistent, real release: `3.9.9/apache-maven-3.9.9-bin.zip`. Verified `./mvnw -version` now resolves and runs |
| **The whole ShopFlow CI pipeline never ran on the default branch.** `shopflow.yml` triggered on `push` to `main`, but this repo's default branch is **`master`**. So every commit people actually push (test, validate, build, scan, Terraform) was ungated; the pipeline was decorative on `master`. It only ran on PRs | CI that does not run on the branch you merge to is theatre; the SLO/wrapper bugs above sat on `master` precisely because nothing re-ran there | Added `master` to the `push` branches. All publish/deploy steps stay `if: github.ref == 'refs/heads/main'`, so a `master` push now **verifies** (test on JDK 21, validate, build, scan) but never pushes/signs/deploys. This is also what finally runs finding #1's Java change |

Walk 8 lessons:
- **A passing rules test is not a working alert.** `promtool test rules` proves the PromQL given the series you feed it; it cannot prove those series exist in production. Where an alert depends on a specific metric/bucket, pin that dependency with a test against the real exporter config (as the notification-service test already does for the Kafka-lag gauge).
- **`cmd | tail` hides exit codes.** The first "baseline green" here was a lie: `./mvnw … | tail -60` reports *tail's* exit code (0), while mvnw had actually died at the broken wrapper. Check the real status, not the tail.
- **Histograms need declared SLO boundaries.** Never query a histogram at an `le` you did not explicitly configure; the default bucket set is an implementation detail that can change under you.

### Honest status (Walk 8, what is NOT verified here)

- The Java change (SLO boundary + the new test) could not run on this machine (only JDK 17/8; Docker daemon off). Finding #3 made the first-ever `master` run of the pipeline happen, and its **`Unit + integration tests` job passed on Temurin 21** — so the SLO bucket fix and its guard test are **verified in CI** (run `39421250786`).
- The Maven wrapper fix **was** verified here (`./mvnw -version` prints Maven 3.9.9 and runs).
- As predicted, enabling CI on `master` surfaced **pre-existing** failures in three tooling-heavy jobs that had never run on `master` before: `k6 inspect` (loadtest), `tflint` (AWS ruleset), and `helm lint/template | kubeconform`. These are not caused by this walk's changes (the Java tests, Terraform fmt/validate, and the kustomize/kyverno/promtool validate steps all passed). They need the CI job logs to fix precisely (the sources read clean), so they are logged as triage items in `STATE.md` §4 rather than blind-patched.

## Walk 9: auditing the new comprehension docs against the source (Oct 29, 2026)

Scope: the just-added `docs/comprehension/` (15 topics, each `comprehension.md` + `notes.md`). These docs make many specific claims about the real ShopFlow code and ship copy-paste "Tod ke dekho" commands, none of which had been run on this machine (no Docker/JDK here). A doc that misstates the code is worse than no doc — interviewers probe exactly the claim you got wrong. So this walk verified the load-bearing facts and commands against the source.

| Found | Why it mattered | Fix |
|---|---|---|
| **Topic 05's raw-concurrency demo was placed *after* `make loadtest`, which drains PS5 stock to 0.** The 20-parallel-reserve block then hits 0 stock → every call returns `409`, so it would show "0 reserved," not the "exactly 3" the lab claims to prove | A hands-on that contradicts its own stated result teaches the opposite lesson and makes the reader distrust the whole doc | Reseed note added (`make down && make up` so PS5 = 3), and the block now stands as an independent demo |
| **Same block backgrounded 20 `curl`s piped straight into `sort` with no `wait`.** The pipe's subshell can close before the background jobs finish, so the counts under-report non-deterministically | A flaky lab command that "sometimes" shows the right thing is a bug report waiting to happen | Wrapped as `{ for …; do curl … & done; wait; } \| sort \| uniq -c` so every request completes before counting |
| **Same block said success was `200/201`.** `ReservationController.reserve` is `@ResponseStatus(HttpStatus.CREATED)` — it is always **201** (the race-loser path returns 201 too) | A wrong status code is the kind of detail an interviewer uses to check if you actually ran it | Corrected to "3×`201`, 17×`409`, never an oversell" |

Verified correct (claims that held up against source — spot-checked, no change needed):
- Endpoints/paths: `POST /api/v1/orders`, `DELETE /api/v1/orders/{id}`, `GET /api/v1/products/{sku}`, `POST /api/v1/reservations` (201) + `DELETE /api/v1/reservations/{orderRef}` (204) — all match the controllers.
- Config values quoted in the docs: reconcile grace `2m` / stuck grace `10m`, rate limiter `50`/`1s`, breaker `50%` over `10` calls / `10s` open, retry `3` attempts `200ms ×2`, Kafka error backoff `500ms ×2` capped `10s`, Redis cache `time-to-live: 60s`, outbox retention `7d`, explicit SLO bucket `distribution.slo.http.server.requests: 1s` — all present in `application.yml` / config as cited.
- Topic/event facts: `orders.events` + `payments.events` topic constants → DLT name `payments.events.DLT` (topic 11) is correct; `FakePaymentGateway` limit `100000` so `PS5-SLIM` (54990) × qty 2 = 109980 is declined (topic 02); `decrementStock` is the atomic `… WHERE quantity >= :qty` (topic 05); readiness group is `readinessState, db` only (topic 07); Grafana Tempo↔Loki linking via `tracesToLogsV2` + `derivedFields` (topic 13).

Walk 9 lessons:
- **A copy-paste lab is a claim too.** Every command that asserts an output ("→ exactly 3") must be reasoned through for state it depends on (here: stock seeded by a prior step) and shell correctness (backgrounded jobs need `wait`), exactly like a code claim.
- **Know your status codes.** "200/201" is a tell that the command was written from memory, not run. `@ResponseStatus` on the controller is the source of truth.

### Honest status (Walk 9, what is NOT verified here)

- The docs are **reviewed against source, not executed.** This machine has no Docker daemon or JDK 21 (per `STATE.md` §3), so none of the `make up` / `kubectl` / `k6` / `promtool` / `cosign` flows were actually run. The fixes above come from reading the controllers/config, not from a green terminal.
- Remaining next step (logged in `SESSION-LOG.md`): run one full topic end-to-end on GitHub Codespaces (`ONLINE-LABS.md` §A) to catch anything that only a real run would surface, before trusting the rest of the hands-on blocks verbatim.

## Walk 10: a deeper pass over every lab command (Oct 29, 2026)

Scope: the `Tod ke dekho` command blocks across all 15 comprehension topics, read **against the actual DB schema and the error/response behaviour of the code** — the parts a reader will literally copy-paste when the "test phase" comes. Walk 9 spot-checked claims; this walk ran down every `psql`, `docker run`, shell helper and "capture the id" step. Six more real defects (none would have produced the stated output):

| Found | Why it mattered | Fix |
|---|---|---|
| **Topic 01 / 03 `psql` ordered a UUID PK `desc` to get "newest."** `outbox_event.id` and `processed_event.event_id` are **uuid**, so `order by id desc` returns arbitrary order, not the newest row the comment promises | The lab's "newest row has published_at = NULL" claim would often point at the wrong row | Order by `created_at` / `processed_at` instead |
| **Topic 03 `select id … from payment`.** The `payment` table has **no `id` column** — its primary key *is* `event_id` (V1__payment.sql). The query errors with `column "id" does not exist` | A copy-pasted query that throws is the first thing a reviewer notices | `select event_id, order_ref, status … order by created_at desc` |
| **Topic 04 / 09 captured the order id from the POST response while inventory was down.** That POST throws `InventoryUnavailableException` → **HTTP 503 problem-detail**, not an order body, so `jq -r '.id'` / `.status` is null — every follow-up (`/orders/$ID`, `{status}`) then breaks | The order *is* saved (FAILED), but you can't observe it the way the lab said; the whole demo falls over | Observe via the DB (`psql … select status from orders`) and, for topic 09, via the **HTTP code + `%{time_total}`** (503 fast-vs-slow shows the breaker) + the breaker metric |
| **Topic 08 `docker run anchore/syft … <image>` had no docker socket.** Syft in a container can't reach the host daemon, so it can't find `shopflow/order-service:local` (the adjacent Trivy line *did* mount the socket) | The SBOM step would just fail to find the image | Added `-v /var/run/docker.sock:/var/run/docker.sock` |
| **Topic 11 `KT bash -c "…"`.** The `KT` helper prepends `/opt/kafka/bin/`, so `KT bash` runs `/opt/kafka/bin/bash`, which doesn't exist | The poison-pill producer step — the climax of the lab — would error out | Pipe host `echo` straight into the container's producer with `docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh` |

Verified correct this pass (no change): DB names/users (`orders`/`orders`, `payments`/`payments`, `inventory`/`inventory`), table/column names actually queried (`orders`, `product`, `outbox_event.published_at`), the k6 thresholds (`count==3`, browse p99<300, order p95<800), breaker/retry/backoff numbers, `KAFKA_NUM_PARTITIONS: 3`, and the business-reject path returning **201 `REJECTED`** (only the inventory-*down* path is a 503).

Walk 10 lessons:
- **Know the primary key.** Half these bugs came from assuming a conventional `id` column; three tables here are keyed by a uuid or a business id. Read the migration before writing the query.
- **An error response is not a data source.** When the failure path returns a 503 problem-detail, the demo has to observe the side effect (the DB row, a metric, the timing), not parse an order out of the error.
- **A shell helper has a contract.** `KT` means "run a `/opt/kafka/bin/*` tool"; feeding it `bash` silently builds a path that doesn't exist.

### Honest status (Walk 10)

- Still **not executed here** — these fixes come from reading the schema and the controllers' error behaviour, not from a terminal. They remove the defects a dry read can find; a Codespaces run (the agreed "test phase") is still what will confirm the rest.

## Walk 11: clearing the red CI that Walk 8 switched on (Oct 31, 2026)

Scope: the three jobs that went red the first time ShopFlow CI ran on `master` (Walk 8, finding #3) and had sat as a `STATE.md` §4 triage item: `k6 inspect`, `tflint` (AWS ruleset), `helm lint/template | kubeconform`. Unlike Walks 9–10, this machine now had a **working Docker daemon**, so each failure was **reproduced with the exact CI tool versions** (k6 latest, `alpine/helm:3.19.0`, `ghcr.io/yannh/kubeconform:v0.6.7`, `tflint` + aws ruleset `0.49.0`) before touching anything — no blind patching.

| Found | Why it mattered | Fix |
|---|---|---|
| **`k6 inspect` failed on a path that doesn't exist.** The `loadtest-syntax` job inherits `defaults.run.working-directory: shopflow`, but its step ran `k6 inspect shopflow/loadtest/flash-sale.js` → resolved to `shopflow/shopflow/loadtest/…` → file not found. The k6 script itself is valid (`k6 inspect loadtest/flash-sale.js` exits 0 locally) | A "syntax gate" that fails on a path bug tells you nothing about the script, and trains people to ignore the job | Step now runs `k6 inspect loadtest/flash-sale.js` (relative to the job's working dir) |
| **`tflint` flagged a real smell:** `elasticache.tf` set `parameter_group_name = "default.redis7"`, the AWS-managed default group you cannot edit (`aws_elasticache_replication_group_default_parameter_group`). Exit 2 → the whole lint job red | You cannot tune Redis (maxmemory-policy, timeouts) on the default group without recreating the cluster; and it kept the lint gate red for everyone | Added an owned `aws_elasticache_parameter_group "redis" {family = "redis7"}` and pointed the cluster at it. Re-ran tflint → **0 issues**; `terraform fmt -check -recursive` clean |
| **`helm lint/template \| kubeconform`: could NOT reproduce.** `helm lint`, and `helm template … \| kubeconform -strict` for both `order-service` and `payment-service` values, all pass locally (helm 3.19.0, kubeconform v0.6.7): 4/4 valid each | Don't "fix" what you can't reproduce — that is how you add churn and new bugs | Left the source untouched; most likely a transient download (`get.helm.sh` / schema fetch) in that CI run. The next `master` run (triggered by this push) is the check |

Walk 11 lessons:
- **Reproduce before you patch.** With Docker up, two of three "failures" turned out to be a CI *path* bug and a real IaC smell; the third did not reproduce at all. Guessing would have "fixed" the wrong files.
- **`working-directory` applies to `run:` steps, not `uses:`.** A job-level default silently rewrites every relative path in that job's scripts; a path that reads right at repo root is wrong inside the working dir.
- **tflint's AWS ruleset is schema-aware.** It catches cloud-correctness smells (`terraform validate` won't): referencing an uneditable default parameter group is valid HCL but poor practice.

### Honest status (Walk 11, what IS and is NOT verified here)

- **Verified locally, in Docker, this session:** `k6 inspect loadtest/flash-sale.js` (exit 0), `tflint` after the fix (0 issues), `terraform fmt -check -recursive` (clean), `helm lint` + `helm template | kubeconform -strict` for both value files (4/4 valid).
- **Not verified locally:** `terraform validate` (needs the AWS provider + modules download) and the full CI pipeline — these run in CI. The k6 path fix is confirmed by logic + the local `k6 inspect` run; CI will re-exercise it.
- The helm job's original failure is **unexplained** (not reproducible locally); if the next `master` run reds again on helm, it is CI-environment-specific and the download step is the place to harden.

## Walk 12: finishing the green, and an action tag that never existed (Oct 31 – Nov 1, 2026)

Scope: close the loop Walk 11 opened — read the CI logs again, confirm each fix, and chase whatever is still red. This is the "don't trust it until the pipeline is green" walk.

First, the Walk 11 fixes were **confirmed green in CI** (run `41214762574`): `Unit + integration tests` (the flaky outbox tests), `Lint` (tflint), `k6 script compiles`, `Validate K8s` (the helm step — so the `tar: helm: Cannot open: File exists` fix landed), and `Terraform fmt + validate` all passed. The helm failure that Walk 11 called "unexplained" **was** the `tar`/`helm/`-directory collision; installing helm into `runner.temp` resolved it.

Then one job was still red:

| Found | Why it mattered | Fix |
|---|---|---|
| **`image` job failed at "Set up job"** with `Unable to resolve action sigstore/cosign-installer@v4, unable to find version v4`. There is no `v4` of that action (v3 is the latest major). The "Install cosign" step is `if: github.ref == 'refs/heads/main'`, but **GitHub downloads every `uses:` action during "Set up job" regardless of its `if:`** — so a bad tag on a step that would be skipped still failed the whole job the first time `image` ran on `master`. matrix fail-fast then cancelled the other three service builds | A build gate that dies at setup tells you nothing about the image, and it was invisible until Walk 8 made CI run on `master` at all | `sigstore/cosign-installer@v4` → `@v3`; added `fail-fast: false` to the `image` matrix so one leg's problem no longer cancels the others (clearer signal). Confirmed `cosign-installer@v4` was the *only* unresolvable action in the run's setup log |

Walk 12 lessons:
- **`if:` does not defer action resolution.** Every `uses:` in a job is fetched at "Set up job"; a wrong tag on a conditional step breaks the job on branches where that step never runs. Pin actions to tags that exist.
- **A matrix without `fail-fast: false` hides the blast radius.** One leg's setup error cancelled three healthy builds and made the cause look service-specific; it was shared.
- **Green is the only proof.** Three of Walk 11's fixes were "verified locally," but only the `master` run proved the helm fix and the Java flaky-test fixes end to end — and surfaced this cosign tag, which no local run touches.

### Walk 12 (cont.): two more, each unmasked by the previous fix

| Found | Why it mattered | Fix |
|---|---|---|
| With `fail-fast: false` the image builds finally ran, and all four died at **Trivy scan** with `could not parse reference: ghcr.io/Rajji01/shopflow-…` — **not a CVE, a casing bug**. OCI references must be lowercase, but `env.IMAGE` used `github.repository_owner` verbatim (`Rajji01`). `metadata-action` lowercases its own tags, so the build passed, but the Trivy/SBOM/ECR steps read `$IMAGE` raw and got an invalid uppercase ref | A scanner that can't parse the ref scans nothing — a green-looking gate that never inspected the image, or (here) a hard failure | A first step sets `IMAGE=ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/shopflow-<svc>` (bash lowercase) so every step shares one lowercase ref |
| Boring-file sweep (migrations, NetworkPolicies, backup CronJob). Migrations and NetworkPolicies were sound. The **nightly `pg_dump`** wrote straight to `/backups/$db.dump`; a dump that fails midway (DB blip) leaves a **truncated `*.dump`** that a restore would happily pick as a good backup | A backup you can't trust is worse than none — you find out during the restore, mid-incident | Dump to `$db.dump.part`, `mv` to `.dump` only on success (atomic); sweep stale `.part` files. A failed dump now never appears as a backup |

Also confirmed sound this sweep (no change): Flyway migrations use the expand pattern with backfill + defaults (safe on a live table, H2-parseable); NetworkPolicies are default-deny ingress+egress with per-service allow-lists and correct ports (8081/8082/8083/8084, 5432, 9092, 6379, 8180, DNS). Open note for a later walk: `OutboxRelay.publishPending()` holds its `@Transactional` across the Kafka `send().get(5s)` for the whole batch — bounded (it returns on the first failure) but worth moving the broker I/O out of the DB transaction if the pool is ever pressured.

### Walk 12 (cont. 2): the scan that finally ran found real CVEs

Once the ref was lowercase, Trivy actually scanned and failed with **14 fixable findings (7 HIGH, 7 CRITICAL)** — all in Spring Boot's transitive Java deps, not the OS layer:

| Dependency | CVEs | Fixed in | How handled |
|---|---|---|---|
| jackson-core / jackson-databind | 89407, 89425, 68497, 91776, 91777 | 2.21.7 | `jackson-bom.version` override in `shopflow/pom.xml` |
| netty-codec / netty-handler | 59901, 75595 (CRIT) | 4.1.137.Final | `netty.version` override |
| tomcat-embed-core | 65182, 65637, 65905, 68525 | 10.1.59 | `tomcat.version` override |
| postgresql | 54291 | 42.7.12 | `postgresql.version` override |
| spring-webmvc | 47884 (CRIT), 47890 | 7.0.9 (Spring 7 / Boot 4) | **triaged** in `/.trivyignore` — no fix in our 6.2.x line |
| lz4-java, snappy-java, httpcore5/-h2 | 106451, 108106, 93451, 54399, 54428 | 1.11.4 / 1.1.10.9 / 5.4.3 | httpcore5 via `httpcore5.version` property (Boot-managed). **lz4/snappy are NOT Boot-managed** (kafka-clients pins them) so the property no-op'd — pinned instead via `<dependencyManagement>`. These hit only the 3 Kafka services; inventory (no Kafka client) went green first |

Walk 12 (cont. 3) lesson: **a version property only works if the BOM declares it.** `jackson-bom`/`netty`/`tomcat`/`postgresql`/`httpcore5` are Spring-Boot-managed, so a property override moved them; `lz4-java` and `snappy-java` are pinned by `kafka-clients` itself and Boot doesn't manage them, so the property was silently ignored (the jars stayed vulnerable) until they were forced through `<dependencyManagement>`. Verify the override actually changed the jar, don't assume.

And one more, cont. 5: even the `<dependencyManagement>` pin missed `lz4-java` at first because I used the conventional coordinate `org.lz4:lz4-java`; the jar is actually published/declared as **`at.yawk.lz4:lz4-java`** (what Trivy and the jar's own POM report), so the pin didn't match. `snappy-java` cleared because its coordinate *did* match. Lesson: **pin the exact coordinate the tooling reports, not the one you remember.**

### Outcome — the pipeline is green

Run `41297074849` (2026-11-01) is the first **fully green** `master` run since Walk 8 switched CI on there: tests, lint/tflint, k6, validate (kustomize/kyverno/promtool/helm), terraform, and all four image builds + Trivy scan + SBOM pass; `Pin prod overlay` stays skipped (main-only). It took **eight** chained fixes — each one only visible after the previous unblocked the job behind it (setup → build → scan → each dependency). The meta-lesson of Walk 12: a long-dark pipeline doesn't have one bug, it has a stack of them, and "green" is the only signal that you reached the bottom.

The first four are patched by overriding Spring Boot's managed versions to the exact fixed releases Trivy named (surgical, reversible once a Boot patch ships them). The spring-webmvc pair has no fix below Spring 7, so it is a *documented, scoped* accept in `.trivyignore` (new CVEs still fail the build) with the Boot 4 upgrade tracked in `STATE.md` §4 — not a blanket mute.

Walk 12 (cont. 2) lessons:
- **A scanner that can't parse the ref is a scanner that found nothing.** The "CVE" failure only became real after the casing fix; always confirm the tool actually ran.
- **Patch transitive CVEs at the BOM, triage only the unfixable.** Overriding managed versions is the honest fix; `.trivyignore` is for what genuinely has no fix in your major line — and it must be documented and bounded, never a catch-all.

## Walk 13: the other pipeline that was quietly red all along (Nov 1, 2026)

Scope: with `shopflow` finally green, check the repo's *other* workflow. `.github/workflows/ci.yml` (the "basics" CI for `Devops/` and `microservice01/`) runs on **every push**, so my own docs commits were triggering it — and it was failing.

| Found | Why it mattered | Fix |
|---|---|---|
| `Build & test Devops` passed `mvn verify` but died at **`docker build -t Devops:ci .`** with `invalid tag "Devops:ci": repository name must be lowercase`. Same casing class as Walk 12's image-ref bug, but here the matrix value `Devops` (a directory name with a capital) was used directly as a Docker tag. `microservice01` (lowercase) would have passed but was cancelled by matrix fail-fast, so the whole workflow showed red | `ci.yml` runs on every push, so this had been **red since the file was added** — invisible because all the attention was on the louder `shopflow` pipeline. A second workflow sitting red trains people to ignore the ✗ | Lowercase the tag (`docker build -t "${SERVICE,,}:ci"`), and `fail-fast: false` on the matrix so one service's failure stops hiding the other |

Walk 13 lessons:
- **Check every workflow, not just the loud one.** A repo can have more than one pipeline; a quiet one can sit red for weeks while a green badge elsewhere lulls you.
- **The lowercase-tag rule bites anywhere a name with capitals becomes a Docker tag** — repo owner (Walk 12) or a matrix/dir name (here). Lowercase at the point of tagging.

## Walk 14: the SLI that its own rate limiter could fool (Nov 1, 2026)

Scope: close the documented open item "the checkout SLI counts any slow non-5xx (incl. 4xx/429) as bad" — now doable because `promtool` runs in Docker (CI's `prom/prometheus:v3.5.0`).

| Found | Why it mattered | Fix |
|---|---|---|
| The checkout SLI's "bad" term was `5xx + (non-5xx total − non-5xx under 1s)`, and its denominator was **all** POST requests. So a flash-sale burst of **429s** (the rate limiter doing its job) was counted two wrong ways: a *slow* 429 landed in "bad" (**falsely burning** the budget), and every 429 sat in the denominator (**diluting** the ratio, masking real 5xx). The SLI could be fooled by our own protection, in both directions | An SLO you can trip by rate-limiting — or hide a real outage behind — is not measuring availability | Redefined valid events as **2xx or 5xx only** (`status=~"[25].."`), "bad" as `5xx + slow-2xx`; 4xx and 429 are excluded from **both** numerator and denominator. Added alert test #7 (a slow-429 burst with no 5xx **must not page**) — it fails on the old SLI, passes on the new one. `promtool check rules` + `test rules` green in Docker |

Walk 14 lessons:
- **A request-based SLI must define "valid events."** Good / total only means something if `total` excludes what isn't the service's job — client 4xx and your own 429 throttling — from *both* sides. Otherwise your protection mechanism either burns or hides the error budget.
- **Pin the intent with a test that fails on the old code.** Test #7 would have fired on the previous SLI; that is what makes it a regression guard, not decoration.

## Walk 15: the quieter files (Nov 1, 2026)

Scope: a sweep of the files nobody had re-read this cycle — `docker-compose.yml`, `Jenkinsfile`, `argocd/`, `init-db.sh`, the prod `external-secret.yaml`. Mostly **sound**, which is itself a finding worth recording:
- `docker-compose.yml` — the `order-service` trick `extra_hosts: localhost:host-gateway` + `JWT_ISSUER_URI: http://localhost:8180` looked wrong but is correct: `localhost` resolves to two addresses, so the in-container JWKS fetch fails on 127.0.0.1 and falls through to the host-gateway (Keycloak), while the app's own `localhost:8081` still binds. No change.
- `Jenkinsfile` — notably **did not** fall into the casing trap that bit GitHub Actions: `REGISTRY = 'ghcr.io/rajji01'` is hard-lowercased, and `trivy image` runs at repo root so it picks up `.trivyignore`. Consistent with the fixes. No change.
- `argocd/application-prod.yaml` — sound (prune + selfHeal + `ignoreDifferences` on `/spec/replicas` for the HPA).
- Trivial: the prod `external-secret.yaml` comment said "three keys" but syncs four — corrected.

## Walk 16: the policy that ignored init containers (Nov 1, 2026)

Scope: the admission policies (`k8s/policies/kyverno/` + the Gatekeeper equivalent). STATE §2 claims both enforce "the same rule"; this walk checked that claim against the Pod spec.

| Found | Why it mattered | Fix |
|---|---|---|
| **`disallow-latest-tag` only inspected `spec.containers`.** Both the Kyverno `pattern` and the Gatekeeper Rego iterated `spec.containers[*]` only — so a Pod with a **`:latest` (or untagged) `initContainer`** (or ephemeral container) passed admission clean. The "no :latest, always rollback-able" guarantee had a hole exactly where migration/setup init-containers live | A policy that only half-covers a Pod gives false confidence; init-containers run with the same blast radius and are a common place to paste a quick `busybox:latest` | Kyverno: added conditional anchors `=(initContainers)` / `=(ephemeralContainers)` to both rules (absent lists are skipped, so valid Pods aren't rejected). Gatekeeper: an `all_containers` set unioning the three container types. **Verified** with `kyverno apply` v1.19.1 in Docker: a new `bad-initcontainer-pod` (valid app container, `:latest` init) now **fails**, while valid Pods with/without init-containers **pass**. Pinned in CI (a dedicated assertion) |

Walk 16 lessons:
- **A Pod is more than `spec.containers`.** Any policy over images/security must also cover `initContainers` and `ephemeralContainers`, or it is bypassable by construction.
- **Conditional anchors, not required fields.** `=(initContainers)` validates the list only when present; writing it as a plain required field would have rejected every Pod without init-containers. The good-Pod test (with and without init) is what proved that.
- **Keep the two policy engines actually equivalent.** The claim "Kyverno and Gatekeeper enforce the same rule" (STATE §2) is only true if you re-check it — here they had drifted into the same gap, now both fixed.

## Walk 17: the same hole, one policy over (Nov 1, 2026)

Scope: having found the init-container gap in `disallow-latest-tag` (Walk 16), check its siblings for the same class of bug. `require-probes`/`require-resources` legitimately apply to `spec.containers` only (init-containers run to completion — no readiness probe), but **`require-non-root` should cover every container**.

| Found | Why it mattered | Fix |
|---|---|---|
| **`require-non-root` only enforced `allowPrivilegeEscalation: false` on `spec.containers`.** The pod-level `runAsNonRoot: true` does reach init-containers, but `allowPrivilegeEscalation` is a per-container field — so an `initContainer` that omits it (or sets it true) could **escalate privileges** while the policy reported clean | Init-containers often run setup/migration steps as the first thing in the pod, with the same node access; a privilege-escalation hole there is a real break-in path | Added `=(initContainers)` / `=(ephemeralContainers)` securityContext checks. **Verified** with `kyverno apply` v1.19.1: a new `bad-init-privesc-pod` (valid app container, init without `allowPrivilegeEscalation:false`) now fails at `/spec/initContainers/0/securityContext`; a fully-compliant pod passes. Pinned with its own CI assertion |

Walk 17 lesson: **when you find a gap, grep the whole family.** The init-container blind spot wasn't one policy's bug — it was a pattern (`spec.containers[*]` everywhere). Fix the ones where it matters (image tag, privilege escalation), and consciously leave the ones where it doesn't (probes).

## Walk 18: the Ansible path that assumed a directory (Nov 1, 2026)

Scope: `infra/ansible/` — the "no Kubernetes" deploy path (Docker Compose on a VM via systemd). Read it as "would this actually run on a fresh Ubuntu box?".

| Found | Why it mattered | Fix |
|---|---|---|
| The `docker` role downloaded Docker's GPG key to **`/etc/apt/keyrings/docker.asc` without creating `/etc/apt/keyrings` first.** On a fresh Ubuntu VM that directory doesn't exist by default, so `get_url` fails with "Destination directory ... does not exist" — the playbook dies at the very first provisioning step | The whole VM path is the fallback for teams without K8s; if it can't get past installing Docker, none of it works. Docker's own install docs create this dir explicitly (`install -m 0755 -d /etc/apt/keyrings`) — it was just missing here | Added an `ansible.builtin.file: path=/etc/apt/keyrings state=directory mode=0755` task before the key download. Verified `ansible-lint` (production profile) stays green: 0 failures |

Also noted (left as-is): `compose_profiles` in `group_vars` is vestigial — the compose file defines no `profiles:`, and the systemd unit doesn't reference it. Harmless; flagged for a later cleanup rather than touched blind.

Walk 18 lesson: **read infra as "fresh machine," not "my machine."** The missing `mkdir` is invisible on any box where `/etc/apt/keyrings` already exists (most dev laptops), and only bites the clean VM the role is actually meant for. `--syntax-check` and `ansible-lint` won't catch it — only asking "does step 2 assume what step 1 didn't create?" does.

## Walk 19: Terraform security read — least privilege on the Kafka IAM role (Nov 1, 2026)

Scope: a security read of `infra/terraform/aws/` (RDS, IRSA, security groups, IAM) — the things `terraform validate` and tflint pass on syntactically but can't judge: public access, open SGs, over-broad IAM.

Mostly tight, and worth recording as verified: **RDS** is `storage_encrypted`, `publicly_accessible = false`, SG ingress 5432 only from the EKS node SGs (not `0.0.0.0/0`), Multi-AZ/backups/deletion-protection gated on prod; **External Secrets** IAM is scoped to the one secret ARN; MSK topic/group ARNs are scoped to `orders.events*` / `payments.events*` and the three consumer groups. One real finding:

| Found | Why it mattered | Fix |
|---|---|---|
| The kafka-clients IRSA policy used **`kafka-cluster:*Topic*`**, which silently includes **`DeleteTopic`** and `AlterTopic*`. A producer/consumer never needs those; a compromised service pod could delete its own event topic (`orders.events` / `payments.events`) | Least privilege: the blast radius of a popped pod should be "read/write its topics," not "destroy the event log." Wildcards in IAM actions hide exactly this | Replaced the wildcard with the explicit set a client needs — `CreateTopic` (auto-create), `DescribeTopic`, `DescribeTopicDynamicConfiguration`, `WriteData`, `ReadData` — dropping Delete/Alter. `terraform fmt` clean; `validate` + tflint run in CI |

Walk 19 lesson: **an IAM action wildcard is a blind spot the linter waves through.** `*Topic*` reads as "topic stuff" but grants destruction; enumerate the verbs a workload actually performs. (Resource scoping here was already good — it's the *action* wildcard that leaked.)

## Walk 20: the refund that could never fail (Nov 1, 2026)

Scope: the last long-standing open item — "refund gateway failures are unmodelled." Reading `payment-service`'s refund path turned up more than a missing feature: a **correctness bug**.

| Found | Why it mattered | Fix |
|---|---|---|
| `OrderConfirmedListener.refund` called `gateway.refund(...)` **and ignored its `Result`** — it marked the payment `REFUNDED` and published `PaymentRefunded` unconditionally. With the fake gateway (always ok) this looked fine, but against a real processor a **declined refund would be reported as success**: the order flips to `REFUNDED`, the customer's money is still with us, and nothing pages anyone | A refund you claim happened but didn't is the worst kind of money bug — silent, customer-facing, and discovered only in a dispute | Check the `Result`. On success: unchanged. On decline: publish **`PaymentRefundFailed`**, leave the payment `CAPTURED` (so a redelivered `OrderRefundPending` retries) and the order `REFUND_PENDING` — the existing `OrdersStuckConfirmed` alert (it already counts REFUND_PENDING) pages a human. order-service gains `onRefundFailed` (record + `orders.refund.failed` metric; no state change). A `payment.refund-limit` (test profile) makes the fake gateway decline a PS5 refund; `declinedRefundPublishesRefundFailedAndKeepsPaymentCaptured` (in the existing test class — reusing its context rather than spinning a second embedded Kafka) proves `PaymentRefundFailed` is emitted and the payment stays `CAPTURED`, never `REFUNDED` |

Walk 20 lessons:
- **Check the return value of anything that moves money.** `gateway.refund(...)` returned a `Result` that was dropped on the floor; the fake always-succeeds gateway hid it in every test until one deliberately declined.
- **"Unmodelled failure path" often means "silently mishandled," not "absent."** The code did run on a declined refund — it just lied about the outcome.
- **Reuse the limbo you already built.** No new alert was needed: REFUND_PENDING was already counted by `OrdersStuckConfirmed`, so a failed refund lands exactly where a stuck one does — a human with a runbook.

## Walk 21: the outbox insert that did a SELECT first (Nov 1, 2026)

Scope: a read of the persistence entities for JPA gotchas. `OutboxEvent` has an **app-assigned** `@Id UUID` (set in the constructor), no `@GeneratedValue`.

| Found | Why it mattered | Fix |
|---|---|---|
| With an assigned `@Id`, Spring Data's `save()` sees a non-null id and treats the row as existing → calls `merge()`, which issues a **`SELECT` before every `INSERT`**. The outbox is one of the most-written tables (a row per domain event), so every publish paid for an extra round-trip it never needed | Not wrong, but wasteful on the hot write path — doubled statements per event, more DB load exactly where throughput matters | `OutboxEvent implements Persistable<UUID>` with `isNew() == true`. Outbox rows are insert-only (the relay flips `published_at` via dirty checking, never `save()`), so `persist()` is always correct → a single `INSERT`, no `SELECT`. Compiles; existing outbox tests (`publishesOrderEventThroughTheOutbox`, `OutboxCleanupTest`) still pass in CI |

Noted, not changed: `ProcessedEvent` and `Payment` share the assigned-UUID pattern and are also insert-only via `save()`; the same `Persistable` treatment would help, but they are lower-write than the outbox — a follow-up, recorded rather than changed blind.

Walk 21 lesson: **an assigned `@Id` quietly turns `save()` into `merge()`.** If an entity is only ever inserted, say so with `Persistable.isNew()` — otherwise every "insert" is a SELECT + INSERT. Invisible in tests (correct, just extra SQL); you only see it in the query log.
