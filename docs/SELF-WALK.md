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

## Walk 22: keeping the comprehension/debugging docs true after the code moved (Nov 3, 2026)

Scope: after a `git pull` fast-forwarded 31 commits (Walks 11–21 from another session), re-check that the `docs/comprehension/` (01–15) and `docs/debugging/` (D01–D07) material still matches the code it cites — because several of those walks changed code the docs describe. CLAUDE.md §2: a doc that disagrees with STATE gets fixed, not the other way round.

| Found | Why it mattered | Fix |
|---|---|---|
| **comprehension 02 (saga-refund) said the `RefundFailed` path was "an open item in STATE §4."** Walk 20 *implemented* it, so the doc now contradicted both the code and STATE | A reader prepping for an interview would describe a gap that no longer exists, and would mis-state the design (it claimed a `REFUND_FAILED` status that was never added) | Rewrote Q8/answer + E10: on a declined refund the payment stays **`CAPTURED`** (retryable), payment-service emits **`PaymentRefundFailed`**, order stays **`REFUND_PENDING`**, `OrdersStuckConfirmed` pages, a redelivery retries. Verified against `OrderConfirmedListener.refund`, `OrderService.onRefundFailed`, `PaymentEventListener` (routes `PaymentRefundFailed`), and `PaymentServiceApplicationTests.declinedRefundPublishesRefundFailedAndKeepsPaymentCaptured` |
| **comprehension 06 + debugging D05 described the SLI as `5xx + (non-5xx total − non-5xx within 1s)`.** Walk 14 redefined it: valid attempts are `status=~"[25].."`, bad = `5xx + slow-2xx`, with 4xx/429 excluded from both sides | The whole point of the SLO drill is a reader who can read the *actual* recording rule; "non-5xx" would now mismatch the file (`status=~"2.."`) | Replaced "non-5xx" with "2xx" throughout topic 06 (comprehension + notes) and D05; the `le="1.0"` empty-bucket lesson is unchanged |
| **The two series were only linked one way.** `docs/debugging/` pointed to its matching comprehension topics, but no comprehension topic (nor its README) pointed back to the drill that debugs it live | For a study loop ("learn the mechanism → now debug it"), the missing reverse links break the natural next step | Added a "Debug it live → Dxx" line to the 9 topics with a matching drill (01→D02, 03→D07, 04→D01, 05/12→D06, 06→D05, 09/13→D03, 11→D01/D02) and a sibling-series section to the comprehension README |

Verified clean (no change): a link-integrity pass over all 40 comprehension+debugging files — **every relative link resolves**; D03's claim that a *failure-rate* breaker can't catch "slow but up" — confirmed, the inventory breaker config has **no** `slowCallRateThreshold`; no stale test/alert/scenario **counts** in any of my docs; comprehension 04's "orchestrated saga timeout" is still correctly an open item in STATE §4.

Walk 22 lesson: **when you don't own the whole branch, your docs rot silently.** Another session changing the code is exactly when grounded docs go stale — pull first, then diff your citations against the new source. The cheapest guard is to cite file + test names (so a mismatch is greppable) and to re-run a link check after any batch edit.

### Honest status (Walk 22)
- Docs only; **not executed** here (no Docker/JDK — STATE §3, and the standing GUIDED-STUDY note). Fixes come from reading the current source, not a terminal. Not pushed (holding per Abhishek's "no push for an hour").

## Walk 23: the new CORS code, the heap claim, and a notes file the last walk missed (Nov 3, 2026)

Scope: review the code the pull added that touches security (`CorsConfig`, `WebCorsConfig` — CORS is a classic footgun), verify a specific number a debugging drill asserts, and finish the Walk-22 refund sync into the file it missed.

| Found | Why it mattered | Fix |
|---|---|---|
| **comprehension 02 `notes.md` still showed only the happy refund path** — the component diagram listed `PaymentCaptured/PaymentFailed/PaymentRefunded` (no `PaymentRefundFailed`), the `Kaise` step and understanding-check Q5 read as if the decline path were unbuilt. Walk 22 fixed `comprehension.md` but not its sibling `notes.md` | A split-brain topic — one file says "implemented", the other says "where would you add it" — is worse than either alone | Added `PaymentRefundFailed` to the event diagram, the decline branch to the `Kaise` refund step, reframed Q5, and extended the interview line (payment stays `CAPTURED`, no fake `REFUNDED`, redelivery retries) |

Verified clean (reviewed, no change needed):
- **CORS (`CorsConfig` order-service, `WebCorsConfig` inventory-service):** both default to `allowed-origin-patterns: http://localhost:*,http://127.0.0.1:*` (localhost only, **not** `*`), `allowCredentials` left **false** (Bearer tokens, no cookies), overridable via `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, documented "dev only". This is the *safe* shape — it avoids the `allowedOrigins:"*"` + `allowCredentials:true` combination that would let any site ride a session. No finding. (It correctly uses `setAllowedOriginPatterns`, required when a pattern has a wildcard port.)
- **debugging D04 heap claim:** the Dockerfile sets `-XX:MaxRAMPercentage=75` (+ `-XX:+ExitOnOutOfMemoryError`), so heap ≈ 384Mi of a 512Mi limit — D04's "~384Mi, the other ~128Mi is non-heap" is correct, and the explicit `ExitOnOutOfMemoryError` reinforces the drill's "crash, don't limp" point.

Walk 23 lesson: **a two-file topic rots one file at a time.** When a paired doc (`comprehension.md` + `notes.md`) describes the same thing, a sync fix has to touch both — grep the sibling, not just the file you opened. And when you review new security code, name what you *checked for* (origins `*` + credentials), so "no finding" is a verdict, not a shrug.

## Walk 24: the lab console's endpoints, and a drill that existed but nothing listed (Nov 3, 2026)

Scope: the new `labs/` folder is what Abhishek will actually click during the "test phase", so verify the console's API calls against the real controllers; and close the loop on debugging **D07** (added + pushed earlier, `07761ba`) whose index entries were never updated.

| Found | Why it mattered | Fix |
|---|---|---|
| **D07 (duplicate-emails drill) existed as a file but no index referenced it.** `docs/debugging/README.md` (table + Status), `docs/INDEX.md`, `docs/STATE.md §1`, and `labs/README.md` all still said "D01–D06" — and worse, the debugging README listed "duplicate charges" as a *future* drill when D07 **is** exactly that, already written | An un-indexed doc is an invisible doc; the reader never reaches D07, and the "Aage (planned)" line promises something that's already done — an overclaim-in-reverse | Added the D07 row to the debugging index, moved it out of the "planned" list, and bumped "D01–D06" → "D01–D07" in INDEX, STATE §1 and labs/README |

Verified clean (reviewed, no change):
- **`labs/lab-console.html`** API calls all match the code: `http://localhost:8081` `/api/v1/orders` (+ `/{id}`), `http://localhost:8082` `/api/v1/reservations` (+ `/{orderRef}`), Keycloak `8180` `/realms/shopflow/protocol/openid-connect/token`, the `Idempotency-Key` header. Ports and paths are correct, so the console will actually talk to the stack when Abhishek runs it. No change.

Walk 24 lesson: **"built and pushed" isn't "discoverable."** A doc added without updating every index that enumerates its series is stranded — and a "planned" list that still names the thing you just built quietly lies. When you add item N, grep the ranges (`D01–D0x`, counts) the same way you grep file links.

### Honest status (Walks 22–24)
- All three walks are **docs only**, reviewed against current source, **not executed** (no Docker/JDK; GUIDED-STUDY). Committed locally, **not pushed** (holding per Abhishek's "no push for an hour"); origin is ahead-counter clean once pushed later.

## Walk 25: verifying the new study docs tell the truth (Nov 3, 2026)

Scope: the pull added big revision docs (`LLD-DESIGN-PATTERNS.md`, etc.) Abhishek will study from; spot-check their most *checkable* claims against the code, because a confidently-wrong interview line is worse than none. No fixes — a clean verification pass (recorded because the claims are interview-load-bearing).

Verified correct (no change):
- **LLD #7 "Optimistic Locking (@Version) → 409":** `orders` has a `version` column (V1 migration) and `GlobalExceptionHandler` maps `OptimisticLockingFailureException` → `HttpStatus.CONFLICT` with the exact "@Version mismatch … loser gets 409" comment. The claim holds end to end.
- **The whole error→status map my comprehension/debugging docs lean on**, read off the one handler: `OrderNotFound`→404, `OrderNotCancellable`/`DataIntegrityViolation`/optimistic-lock→409, `InventoryUnavailable` & downstream `RestClient`/`CallNotPermitted`→503 (the 503 carries an `orderId` property, not an order body — consistent with D04/topic-04 observing via the DB), `IdempotencyKeyConflict`→422, `RequestNotPermitted`→429. Every status code the labs assert is backed by this handler.
- **LLD #1/#4/#8/#15 vs code:** `PaymentGateway` Strategy + `charge`/`refund` `Result` (Walk 20), `onPaymentFailed` compensation, `decrementStock` atomic conditional UPDATE, and the OCP example (`PaymentRefundFailed` = one new switch case) all match.

Walk 25 lesson: **a study doc is a set of claims, each one greppable.** The fastest way to trust (or break) "in my project, X does Y" is to open the one file that would make it false — here, the single `GlobalExceptionHandler` validates a dozen status-code lines at once.

### Honest status (Walks 22–25)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Walks 22–25 committed **locally, not pushed** (holding per "no push for an hour") — 4 local commits ahead of `origin/master`.

## Walk 26: the visual flow that still drew only the happy refund (Nov 3, 2026)

Scope: the same Walk-20 refund-decline sync, chased into the other study docs (`VISUAL-FLOWS.md`, `HLD-SYSTEM-DESIGN.md`) — because a state diagram people learn from is exactly where a missing branch mis-teaches.

| Found | Why it mattered | Fix |
|---|---|---|
| **`VISUAL-FLOWS.md`'s order state machine + refund sequence drew only `REFUND_PENDING → REFUNDED` (PaymentRefunded).** No decline branch — a reader studying the diagram would believe a refund always succeeds, the same gap Walk 22/23 fixed in comprehension 02 | A visual is the thing people memorize; an incomplete state machine is a wrong mental model that surfaces in an interview | Added the self-loop `REFUND_PENDING --> REFUND_PENDING: PaymentRefundFailed (declined → stays CAPTURED, alert + retry — Walk 20)` to the state diagram, a note to the saga sequence, and the decline clause to the "Kaise" idempotency line |

Verified correct (no change): `HLD-SYSTEM-DESIGN.md` **already** carries the decline path ("refund declined: `PaymentRefundFailed`, order REFUND_PENDING, human (Walk 20)"); and a sweep of all three big docs for stale **SLI** wording (Walk 14's `non-5xx`→`2xx`) found **none** — only my own comprehension 06/D05 had used the old term, and those were already fixed.

Walk 26 lesson: **one code change fans out across every doc that drew the old picture.** After fixing a mechanism doc, grep *every* place that renders the same flow — prose, a Mermaid diagram, a sequence note — not just the first file. The refund-decline of Walk 20 touched four of my files and one of theirs; each needed finding separately.

### Honest status (Walks 22–26)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Five local commits ahead of `origin/master`, **not pushed** (holding per "no push for an hour"). When Abhishek lifts the hold, one `git push` ships Walks 22–26 together.

## Walk 27: the study doc was right, and it exposed a gap in mine (Nov 3, 2026)

Scope: review `INDEXING-HIBERNATE.md` (the other session's 420-line DB/JPA doc) against the code, and act on anything it surfaces.

Verified correct (no change to the study doc — its checkable claims all hold):
- The whole §3 index table against the migrations: `orders` PK/`order_ref`/`idempotency_key`/`idx_orders_created_at` (V1), `idx_orders_customer_created`/`idx_orders_status_updated` (V3), `payment` PK `event_id` + unique `order_ref` (V1), `outbox_event idx_outbox_pending` (V2), `processed_event` PK (V5).
- **§11's SKIP LOCKED claim** — the load-bearing one: `OutboxRepository.findUnpublished` really is `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints(lock.timeout = "-2")` over `where publishedAt is null order by createdAt`, and its own Javadoc says "renders as FOR UPDATE SKIP LOCKED … several order-service pods can run the relay … without publishing the same row twice." Accurate.
- §10 `@Version` → 409 (already confirmed Walk 25), §12 `decrementStock` flush/clear, §13 `TransactionTemplate` self-invocation.

| Found | Why it mattered | Fix |
|---|---|---|
| **My comprehension 01 (outbox) never mentioned the multi-pod relay / `SKIP LOCKED`.** The passage described a single relay; it omitted that the HPA runs the relay on every replica and `FOR UPDATE SKIP LOCKED` is what stops concurrent double-publish | It's an interview-gold detail (pessimistic lock + SKIP LOCKED, multi-consumer safety) that the grounded code has and the comprehension didn't teach — a hole exactly where a follow-up question lands | Added a passage paragraph, a new Transfer question (Q10: "3 replicas each run the relay — what stops triple-publish, and why no blocking?") with its answer (renumbered the spot-the-flaw to Q11), an interview-concepts bullet, and the `Kaise`/understanding-check/interview-line in `notes.md` |

Walk 27 lesson: **reviewing someone else's doc is also a mirror.** The fastest way to find what *your* material is missing is to read a parallel doc on the same system — INDEXING taught SKIP LOCKED thoroughly, which made the silence in comprehension 01 obvious. Cross-read, don't just self-read.

### Honest status (Walks 22–27)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Six local commits ahead of `origin/master`, **not pushed** (holding per "no push"). One `git push` ships Walks 22–27 together when the hold lifts.

## Walk 28: three new debugging drills — and a bug I wrote into one (Nov 3, 2026)

Scope: extend the debugging series with the three highest-value planned drills (the "real-time debugging" skill Abhishek is training for), grounded in shopflow, and index them properly this time (the D07 miss from Walk 24 is the counter-example).

Built: **D08** (lag on one partition only → poison pill + a DLT topic that didn't exist → head-of-line blocking is *per-partition*), **D09** (blanket 401 after an IdP migration → wrong `JWT_ISSUER_URI`; teaches 401=auth-layer vs 403=authz-layer, lazy-JWKS "Ready but not working"), **D10** (Redis down → DB on fire → fail-open cache converts a cache outage into DB *load*, not errors; hit-rate `1/(1−h)` = blast radius, hot-key stampede, RED-to-triage/USE-to-localize).

| Found | Why it mattered | Fix |
|---|---|---|
| **D08's first draft invented a bug that isn't in the code.** I wrote the poison pill as "unknown SKU → `priceOf` returns null → NPE in `charge()`". But `PriceList.priceOf` is `getOrDefault(sku, 999.00)` — it **never returns null**; there is no such NPE | A debugging drill whose "root cause" can't actually happen in the code teaches a fiction — the exact opposite of "grounded in real ShopFlow" | Re-grounded the pill to a **malformed payload** that `objectMapper.readValue` can't parse (a real poison pill the `DefaultErrorHandler` would dead-letter *if the DLT topic existed*), and pointed the recurring fix at the Schema-Registry open item instead of a non-existent `priceOf` bug |

Indexed in the same commit (the Walk-24 lesson applied): debugging `README` table + Status (now D01–D10, the three dropped from "planned"), `INDEX.md`, `STATE.md §1`, the comprehension README pairing line, and reverse "Debug it live" links on topics 07/10/11/12. Link-integrity re-run over 43 files: all resolve.

Walk 28 lesson: **the fastest way to write a fake drill is to recall the code instead of reading it.** I "remembered" an unknown-SKU NPE; the actual `getOrDefault` default makes it impossible. Open the file the claim depends on *before* writing the claim, not after — the same discipline that catches someone else's stale doc catches your own fresh one.

### Honest status (Walks 22–28)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Seven local commits ahead of `origin/master`, **not pushed** (holding per "no push"). One `git push` ships Walks 22–28 together when the hold lifts.

## Walk 29: three more debugging drills (D11–D13) (Nov 3, 2026)

Scope: finish the debugging series' planned list — the three remaining high-value scenarios — each grounded in shopflow and indexed in the same commit.

Built: **D11** (inventory blips → order-service has zero endpoints → a readiness probe pointed at the *aggregate* `/actuator/health` coupled availability to a downstream → cascade; teaches "all replicas unhealthy at once + each healthy = shared config, not instance failure"), **D12** (DLT at 9,000 records, zero lag, no alert → a working dead-letter handler silently eating a systematic failure after a field *rename* that `ignoreUnknown` doesn't protect against → recovery is **fix-then-replay**, safe only because consumers are idempotent → alert on the escape hatch), **D13** (rollout "in progress" 20 min → **paused-by-design vs auto-aborted vs genuinely stuck**, read from one `kubectl argo rollouts get`; don't fight your own gates; `promote` trades soak time for speed).

Indexed in the same commit: debugging `README` (D01–D13, planned list refreshed), `INDEX.md`, `STATE.md §1`, the comprehension README pairing line, and reverse links on topics 07/11/15. Link-integrity over **46** files: all resolve. No invented bugs this time — each root cause (aggregate-health readiness, field-rename past `ignoreUnknown`, canary pause semantics) is a real property of the code/manifests cited.

Walk 29 lesson: **a debugging-drill series is only as trustworthy as its weakest invented symptom** (Walk 28's `priceOf` NPE). D11–D13 were written straight from the manifests/handlers they cite (`deployment.yaml` readiness group, `KafkaErrorConfig` + the `ignoreUnknown` copies, `order-service-canary.yaml` steps), so the "root cause" in each is something that can actually happen here.

### Honest status (Walks 22–29)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Debugging series now **D01–D13**; comprehension 15 topics, all synced/enriched; 46-file link check clean. Local commits ahead of `origin/master`, **not pushed** (holding per "no push"). One `git push` ships Walks 22–29 together when the hold lifts.

## Walk 30: two more k8s drills (D14, D15), both read off the manifest (Nov 3, 2026)

Scope: two more debugging drills, deliberately **grounded in the real manifest** (the Walk-28 lesson — write the symptom from the file, not from memory).

Built: **D14** (every deploy throws a few seconds of 502/connection-reset → a "tidy-up" removed the container `lifecycle.preStop: sleep 5`, so SIGTERM wins the race against endpoint-deregistration; teaches 502≠500, preStop=stop-new vs graceful=finish-old, zero-downtime = add-before-remove + stop-new + finish-old), **D15** (replicas flap 2↔5 all day on flat traffic → a removed `behavior.scaleDown.stabilizationWindowSeconds: 300` + a JVM's twitchy CPU hovering at the 70% target; teaches HPA's up-fast/down-slow asymmetry, why raising the target only masks it, and request-rate as a smoother metric than CPU).

Both root causes are **removed-config regressions against the actual files**: `deployment.yaml` really has `lifecycle.preStop: sleep 5` + `server.shutdown: graceful` (D14), and `hpa.yaml` really has `scaleDown.stabilizationWindowSeconds: 300` + CPU `averageUtilization: 70` of a `250m` request (D15) — so "someone deleted it" is a plausible, checkable regression, not an invented bug.

Indexed in the same commit: debugging `README` (D01–D15), `INDEX.md`, `STATE.md §1`, comprehension README pairing + reverse links on topics 07/15. Link-integrity over **48** files: all resolve.

Walk 30 lesson: **the best drills are "someone deleted the line that was protecting you."** Reading the manifest first (`preStop`, `stabilizationWindowSeconds`) turns a vague "deploys are flaky" into a specific, grounded regression — and doubles as teaching what that line is *for*.

### Honest status (Walks 22–30)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Debugging series now **D01–D15** (15 drills); comprehension 15 topics, all synced/enriched; 48-file link check clean. Local commits ahead of `origin/master`, **not pushed** (holding per "no push"). One `git push` ships Walks 22–30 together when the hold lifts.

## Walk 31: QA sweep — do the drills cite alerts that actually exist? (Nov 3, 2026)

Scope: having written 15 drills quickly, grep every **alert name** they cite and check it against `shopflow/monitoring/*.yml` — the fastest way to catch an over-claim before it becomes study material.

| Found | Why it mattered | Fix |
|---|---|---|
| **D04 said `KubePodCrashLooping` "fires" as if it were a ShopFlow alert.** It isn't in `monitoring/` — it's a standard **kube-prometheus-stack** platform alert; ShopFlow's own rules are app-level (`OrdersStuckConfirmed`, `OutboxBacklogGrowing`, `KafkaConsumerLagHigh`, `CheckoutSLOBurnRate*`, `PaymentFailureRateHigh`, `InventoryCircuitOpen`, `ServiceDown`, `HighErrorRate`, `HighP99…`) | A reader would claim an alert the project doesn't define — the kind of specific-but-wrong detail an interviewer catches | Reworded D04 to attribute `KubePodCrashLooping` to the platform (kube-prometheus-stack) and note ShopFlow's rules are app-level, not pod-level |

Verified correct (no change): the other four alert names the drills use (`OrdersStuckConfirmed`, `OutboxBacklogGrowing`, `KafkaConsumerLagHigh`, `CheckoutSLOBurnRateFast`) are all defined in `monitoring/`; and the metric names (`orders_stuck_confirmed`, `outbox_unpublished`, `resilience4j_circuitbreaker_state`, `payments_duplicates_total`, `inventory_reservations_rejected_total`, `http_server_requests_seconds_*`) all map to real `MeterRegistry`/Micrometer sources. (Noted for later: `InventoryCircuitOpen` is a real alert the resilience drills D03/D09 could cite but don't need to.)

Walk 31 lesson: **a cited alert/metric name is a claim; grep it against the rules file.** Project-specific names (`OrdersStuckConfirmed`) and platform names (`KubePodCrashLooping`) read the same in prose but one is yours and one isn't — say which.

### Honest status (Walks 22–31)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Debugging series **D01–D15**; comprehension 15; all synced/enriched/QA'd; links clean. Local commits ahead of `origin/master`, **not pushed** (holding per "no push"). One `git push` ships Walks 22–31 when the hold lifts.

## Walk 32: verifying the fast-written "Code + dry run" snippets (Nov 3, 2026)

Scope: the enrichment pass added a `Code + dry run` section to every comprehension topic's `notes.md` (01–17), written quickly — some from memory of the code. The D08 fiasco (an invented NPE) is the warning: a snippet that misquotes the code teaches a lie. So grep each snippet's **checkable values** (config constants, method signatures, behaviour) against the real source.

Verified correct — **no fixes** (a clean verification pass; recorded because the snippets are now study material):
- **09 resilience:** `connect-timeout: 1s`, `read-timeout: 2s`, `max-attempts: 3`, `wait-duration: 200ms`, `sliding-window-size: 10`, `minimum-number-of-calls: 5`, `failure-rate-threshold: 50`, `wait-duration-in-open-state: 10s`, `ignore-exceptions: InventoryRejectedException` — all match `application.yml` exactly.
- **11 Kafka:** `new ExponentialBackOff(500L, 2.0)` + `setMaxElapsedTime(10_000L)` — matches `KafkaErrorConfig`.
- **12 caching:** `time-to-live: 60s` — matches inventory `application.yml`.
- **06 SLO:** the fix line `management.metrics.distribution.slo.http.server.requests: 1s` — present and exact.
- **Code shapes:** `OrderService.cancel` (isPaid→requestRefund→REFUND_PENDING; else release+cancel), `OrderService.get` (findById→filter mayAccess→404), `placeOrder` (findByIdempotencyKey + ownership), `decrementStock` (`… where quantity >= :qty`) — all match the real methods. The dry-run traces (PS5 qty1→PAID, qty2→CANCELLED via the 100000 charge limit; breaker open after 50%/10 → fast-FAILED) are consistent with the code.
- Honest caveat kept in mind: a few snippets are **abbreviated** (e.g. 09's `.onStatus(CONFLICT, …)` vs the real `status -> status.isSameCodeAs(HttpStatus.CONFLICT)`; 10's matcher list trimmed) — simplified for readability, not wrong; the exact code is one click away via the cited file.

Walk 32 lesson: **enrichment written at speed is exactly where a D08 hides.** The cheapest insurance is a grep of every constant/signature in a new snippet against its source file — values, not vibes. This pass found them all accurate, which is the result you *verify*, not assume.

### Honest status (Walks 22–32)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). Comprehension **01–20** (01–17 Code+dry-run verified, 02/03/05/10 Test-karo); debugging **D01–D15**; all synced/enriched/verified; links clean. Local commits ahead of `origin/master`, **not pushed** (per "local hi, push mat karna").

## Walk 33: the DB connection pool was "10" in three places — it's 5 (Nov 4, 2026)

Scope: audit the debugging drills (D01–D15) and the new topics (18–20) against source for any claim that drifted — and cross-check **numbers** between docs (the easiest kind of inconsistency to ship).

Verified correct (no change): D12's `@JsonIgnoreProperties(ignoreUnknown = true)` is real on all three event copies (notification `OrderEvent`, order `PaymentEvent`, payment `OrderEvent`) — so its "ignore-unknown tolerates adds, not renames" reasoning is grounded; `JwtRolesConverter` really reads `cognito:groups` else `realm_access.roles` → `ROLE_*` (D09); `hpa.yaml` (min 2 / max 6 / CPU 70 / stabilization 300) and `deployment.yaml` (maxUnavailable 0 / grace 45 / preStop) match D14/D15/topic-18; `backup-cronjob.yaml` and `irsa.tf` match topics 19/20. A **numeric sweep** confirmed the rest are consistent with source: Kafka partitions **3 local** (`KAFKA_NUM_PARTITIONS: 3`) / **6 MSK** (`msk.tf num.partitions=6`), memory **512Mi limit / 384Mi request / ~128Mi non-heap** (D04 vs `deployment.yaml`), and the CI OIDC subject `repo:<repo>:ref:refs/heads/main` + `aud: sts.amazonaws.com` (`ci-oidc.tf`) exactly as topic 20 states.

| Found | Why it mattered | Fix |
|---|---|---|
| **The DB connection pool was cited as "10" in topic 16, D03 and D10, but every service ships `DB_POOL_SIZE=5` in k8s** (`k8s/base/*/deployment.yaml`), and `HLD-SYSTEM-DESIGN.md` states "pool **5**/pod × HPA 10 × services ≈ 100 < max_connections 200". The app *default* is 10 (`application.yml` `${DB_POOL_SIZE:10}`, used only in local compose), so "10" wasn't nonsense — but it **contradicted the architecture doc and the deployed config**, and the "budget" math only works at 5 | A reader quoting "pool of 10" would mismatch both the HLD and the real manifests — and the connection-budget reasoning (why the pool is *small*) falls apart at 10×10×services | Changed the canonical number to **5 per pod** across topic 16 (passage/answers/hands-on/notes/interview-line), D03 (`5/5` saturation), and D10 (`5/5`), each noting the app default is 10 (local compose). Now consistent with `HLD`, `EASY-NOTES`, `SELF-WALK` Walk 2 |

Walk 33 lesson: **numbers are the easiest thing to get inconsistent across many docs, and the easiest to grep.** One config value (`DB_POOL_SIZE=5`) is the source of truth; three docs had drifted to the framework default (10). When a value appears in the architecture doc, make every other doc cite *that*, not the library default.

## Walk 34–35: numeric sweep + citation + HLD audit (Nov 4, 2026)

Scope: extend Walk 33's number check to the rest of the docs and the big study doc.
- **Numeric sweep (clean):** Kafka partitions (3 local / 6 MSK), memory (512Mi/384Mi/~128Mi), rate-limit 50/s/pod, k6 flash-sale 3/300 / `orders_confirmed==3`, p95<800/p99<300, CI OIDC subject — all match source.
- **Path-citation check (clean):** every `` `path.ext` `` citation in comprehension/debugging maps to a real file; the ones a strict resolver flagged (`config/CacheConfig.java`, `security/SecurityConfig.java`, `values/order-service.yaml`, `monitoring/grafana/.../datasources.yml`, …) are intentional **package-tail / ellipsis shorthand**, not broken — each resolves to an existing file.
- **HLD-SYSTEM-DESIGN.md audit (clean):** its "asli numbers" all hold — `KAFKA_NUM_PARTITIONS=3`, pool **5**/pod × HPA 10 × 2 services ≈ 100 < `max_connections=200` (RDS 300), MSK `replication.factor=3`/`min.insync.replicas=2`/`num.partitions=6`, rate 50/s/pod, flash-sale 3/300. HLD was the **reliable reference** here — it's where the correct DB-pool (5) came from, against which topic 16/D03/D10 had drifted (Walk 33 fix).

Walk 34–35 lesson: after one number drifts (the DB pool), **sweep the same class of claim everywhere** — partitions, memory, OIDC subjects, file citations — because a single drift usually means the habit (quoting the library default, abbreviating a path) repeated. This sweep found the suite otherwise consistent.

### Honest status (Walks 22–35)
- Docs/review only; **not executed** (no Docker/JDK; GUIDED-STUDY). All claims re-verified against source; DB-pool number consistent (5/pod); numbers/citations/HLD audited clean; links clean. In-sync with `origin/master` (pushed).

## Walk 36: the refund-failure metric nobody could see (Nov 8, 2026)

Scope: close a gap Walk 20 left. Walk 20 added a `payments.refund.failed` counter when the processor declines a refund, but **no alert, no runbook** — the repo's own rule (STATE §5: a new failure signal gets an alert + unit test + runbook) wasn't met, and the Grafana dashboard doesn't plot it either. So a declined refund was recorded but invisible until `OrdersStuckConfirmed` fired ~15 min later for a different reason.

First, the self-walk also **verified the Grafana dashboard** (`shopflow-red.json`): all 14 panel expressions map to metrics/recording rules that actually exist (`service:*` RED rules, `sli:checkout_error_ratio:*`, `orders_total`/`payments_total`, `outbox_unpublished`, `kafka_consumer_fetch_manager_records_lag_max`, `resilience4j_circuitbreaker_not_permitted_calls_total`, `hikaricp_connections_*`, `orders_stuck_confirmed`). No broken panels.

| Found | Why it mattered | Fix |
|---|---|---|
| `payments_refund_failed_total` had no alert/runbook | A declined refund means a customer is owed money and the order is stuck in `REFUND_PENDING`; relying on the slower `OrdersStuckConfirmed` backstop buries a known, actionable failure | Added a **`PaymentRefundFailed`** alert (`increase(payments_refund_failed_total[15m]) > 0`, severity ticket — money is safe with us, not a 3 AM page, but must not be ignored), two promtool unit tests (ticks up → fires; flat → silent), and a runbook (how to see which orders, why the decline, how to resolve without ever faking REFUNDED). Distinct from `OrdersStuckConfirmed`: faster and names the cause (decline, not lag) |

Walk 36 lesson: **a metric without an alert is a number nobody reads.** Walk 20 did the hard correctness part (stop faking the refund) but left the signal mute; completing the loop is alert + runbook + test, exactly the rule the repo already states. (Verified in CI — no Docker/JDK locally this session.)

## Walk 38: three more entities doing a wasted SELECT before every INSERT (Nov 8, 2026)

Scope: finish the deferred Walk-21 follow-up (STATE §4). Walk 21 made `OutboxEvent implements Persistable<UUID>` so its assigned-UUID `save()` stops doing `merge()`+SELECT. Three other entities share the exact same shape — an **assigned** `@Id` (not generated), **insert-only** lifecycle — and were still paying the merge tax.

The mechanism (clear as whiskey): Spring Data's `SimpleJpaRepository.save()` calls `em.persist()` only when the entity "is new", else `em.merge()`. With no `@Version` and an **assigned** id, its default `isNew()` sees a non-null id and says "not new" → `merge()`, which issues a **SELECT** to check if the row exists before the INSERT. For a row we *know* is brand new (we just minted the UUID), that SELECT is pure waste — and worse, it's one extra round-trip inside the consumer's transaction, on the hot path of every Kafka message.

| Found | Why it mattered | Fix |
|---|---|---|
| `order-service` `ProcessedEvent`, `notification-service` `ProcessedEvent`, `payment-service` `Payment` — all assigned-UUID, insert-only — did `merge()`+SELECT on `save()` | Every consumed event does a `processed_event` insert for idempotency bookkeeping; `Payment` is inserted once per `OrderConfirmed`. That's a needless SELECT per message, in-transaction, on the saga hot path | `implements Persistable<UUID>` + `getId()` returning the id field + `isNew(){return true;}` → `persist()`, a plain INSERT. Verified each is insert-only: both `ProcessedEvent`s are only ever `new`+`save()`; `Payment`'s refund mutates the loaded entity via dirty-checking (`markRefunded`), never `save()`, so forcing `isNew=true` can't corrupt an update path |

Why `isNew(){return true;}` is safe here and not a hack: these rows are **never updated through `save()`**. The idempotency guard is the primary key itself — a duplicate `eventId` fails the INSERT (caught and treated as "already processed"), which is the behaviour we want; it was never relying on merge's SELECT. If any of these ever gained an update-via-save path, `isNew` would have to become `createdAt == null`-style; a comment on each class says so.

**Closure (swept all 7 entities):** of the repo's JPA entities, the four with an assigned id are now all `Persistable` — `OutboxEvent` (Walk 21) + these three (Walk 38). The other three — `Product`, `Reservation`, `Order` — use `@GeneratedValue(IDENTITY)`, so the default `isNew()` already works (id is null pre-insert → persist, non-null post-insert → merge on a real update); `Order` additionally carries `@Version` and is a genuinely-updated aggregate, so its `merge()` is correct and must stay. Nothing left in this class.

Walk 38 lesson: **an assigned `@Id` silently turns every `save()` into a read-then-write.** When a table is insert-only, `Persistable` with `isNew()==true` is the honest declaration of intent and drops a round-trip from the hot path. The tell is "assigned id + no `@Version` + we never call `save()` on a loaded instance." (Verified in CI — no Docker/JDK locally this session; existing idempotency/outbox tests cover correctness.)

## Walk 39: retry without jitter is a thundering herd waiting to happen (Nov 8, 2026)

Scope: the resilience config review. `InventoryClient.reserve/release` is wrapped `Retry(CircuitBreaker(call))`, and the config is otherwise excellent — retry is a **whitelist** (`ResourceAccessException` + `HttpServerErrorException` only, so a 409/404 business "no" is never retried), and the breaker **ignores** `InventoryRejectedException` (out-of-stock doesn't trip the circuit). Verified correct, no change there. But one gap, which the interview notes had themselves flagged as a TODO: **exponential backoff with no jitter.**

| Found | Why it mattered | Fix |
|---|---|---|
| `resilience4j.retry.instances.inventory` had `enable-exponential-backoff` (200ms→400ms) but **no randomization** | When inventory-service blips, *every* in-flight order across *every* order-service pod fails at ~the same instant, then all retry at 200ms, then all at 400ms — synchronized waves that hammer a recovering service exactly when it's weakest (a self-inflicted thundering herd). The breaker helps, but jitter is the cheaper first line | Added `enable-randomized-wait: true` + `randomized-wait-factor: 0.5` → resilience4j builds `IntervalFunction.ofExponentialRandomBackoff`, so each wait is ±50% (200ms→[100,300], 400ms→[200,600]). Pods de-synchronize; load spreads instead of spiking |

Why it's safe with the tests: `application-test.yml` only overrides `wait-duration` (→10ms) and inherits the rest, so the test instance now also randomizes — but every retry/breaker test asserts on **call counts** (`inventory.verify(3, …)`, `verify(1, …)`) and breaker **state**, never on timing. Jitter changes *when* a retry fires, not *how many*; attempt count is still `max-attempts: 3`. The base config already combined `wait-duration` + exponential (CI green), so adding randomized just selects the combined interval function — no startup conflict (the resilience4j issue #2378 conflict is specific to inheriting `wait-duration` from a shared `default` config, which we don't use).

Walk 39 lesson: **exponential backoff spaces out *one* client's retries; jitter spaces out *all* clients' retries.** Without randomization, horizontal scale turns a downstream blip into a synchronized retry storm. It's a one-line config change and the single most common real-world retry mistake interviewers probe for. (Verified in CI — no Docker/JDK locally this session; order-service retry/breaker tests load the context and assert call counts, unaffected by timing.)

## Walk 40: the dead-letter topic nobody was watching (Nov 8, 2026)

Scope: Kafka error-handling review. All three consumers have the same `KafkaErrorConfig` (retry with exponential backoff 500ms×2 capped at ~10s, then `DeadLetterPublishingRecoverer` → `.DLT`) — verified consistent and correct. But Walk 36's question again: **who finds out?** A grep across `alert-rules.yml`, the recording rules, the Grafana dashboard and the runbooks found **zero mention of the DLTs**, and there is no broker-side exporter. EASY-NOTES even says "DLT pe alert/ticket" — the docs promised an alert that did not exist.

| Found | Why it mattered | Fix |
|---|---|---|
| A record moved to `orders.events.DLT` / `payments.events.DLT` produced **no signal anywhere** | Dead-lettering un-blocks the partition, but the event's side effect didn't happen — an email never sent, a payment outcome never applied. Nothing consumes the DLTs, so the event is **silently lost** until a human notices the missing side effect (possibly days later) | 1) each `KafkaErrorConfig` now wraps the recoverer: `deadLetter.accept(record, ex); counter.increment();` → `kafka.dead.lettered` (incremented only **after** the DLT publish succeeded; a failed publish throws → record retried, not counted). 2) `KafkaMessageDeadLettered` alert (`sum by (application) (increase(kafka_dead_lettered_total[15m])) > 0`, ticket, labelled by service). 3) promtool tests 9 (ticks → fires with the right label) + 9b (flat → silent). 4) runbook `KafkaMessageDeadLettered.md` (inspect DLT headers, cause table, idempotent replay) + README row. 5) poison-pill test now also asserts the counter ticks |

Design note: the counter is incremented *after* `accept()` so the metric means "safely parked on the DLT", not "we tried". Alert is a **ticket**, not a page — the pipeline is healthy, but every DLT record is owed a replay. Replay is safe because every consumer is idempotent (`processed_event` / PK dedupe — Walks 38's entities are exactly this bookkeeping).

Walk 40 lesson: **a dead-letter topic without an alert is a graveyard, not a parking lot.** The DLT pattern only "handles" failure if something routes a human to it; otherwise it converts a loud failure (blocked partition) into a silent one (lost event). Same lesson as Walk 36, one level up the stack — and the docs claiming "DLT pe alert" before one existed is exactly the doc-vs-reality drift STATE §2 exists to catch. (Verified in CI — promtool + the extended poison-pill test.)

## Walk 41: the failure signals get a dashboard panel (Nov 8, 2026)

Scope: close the gap Walk 36 itself recorded — "the Grafana dashboard doesn't plot it either." `payments_refund_failed_total` (Walk 36) and now `kafka_dead_lettered_total` (Walk 40) had alerts + runbooks but no place on `shopflow-red.json`, so during an incident you could *get* the ticket but not *see* the signal next to the RED metrics you're already staring at.

| Found | Why it mattered | Fix |
|---|---|---|
| The two "money/data at risk" counters were alert-only, invisible on the dashboard | During triage you correlate: did the refund decline coincide with the breaker opening? with consumer lag? A signal that lives only in Alertmanager can't be correlated visually | New stat panel 12 ("Failure signals (15m): refunds declined / dead-lettered") next to the stuck-orders stat: `sum(increase(payments_refund_failed_total[15m]))` + `sum(increase(kafka_dead_lettered_total[15m]))`, green at 0, red at ≥1 — same threshold style as panel 11. JSON validated (12 panels) |

Walk 41 lesson: **an alert tells you *that* something broke; the dashboard is where you see *what else* broke with it.** Every alerted signal deserves a spot next to the metrics you correlate it against — otherwise the on-call pivots between tools during the worst five minutes.

## Walk 42: the price list that invented prices (Nov 8, 2026)

Scope: payment-service charge/refund paths, fresh eyes. The Walk-20 refund logic and the eventId-PK idempotency are solid (re-verified). Two silent-wrongness problems found, both in what the code does when its assumptions break.

| Found | Why it mattered | Fix |
|---|---|---|
| `PriceList.priceOf` had `getOrDefault(sku, 999.00)`: an unknown sku was **silently charged ₹999** | Inventory's product catalog (Flyway seed) and payment's `PriceList` are two hand-maintained lists. Add a product to inventory, forget `PriceList` → every order for it charges a **made-up price** — the customer pays the wrong amount and nothing anywhere flags it. (Walk 28 had even caught a drill draft wrongly claiming this throws an NPE — it never failed, which is exactly the problem) | `priceOf` returns `Optional`; on empty the charge is **declined** (`no price for sku <X>`) through the normal failure path: FAILED payment row (idempotent via eventId PK), `PaymentFailed` event → order-service releases stock + CANCELLED, `payments_total{status="FAILED"}` feeds the existing `PaymentFailureRateHigh` alert, runbook row added. New test `unknownSkuIsDeclinedNotChargedAMadeUpPrice` in the shared-context test class |
| `refund()`'s no-op paths were **silent**: no payment row at all, or a non-CAPTURED payment → `Optional.filter().ifPresent()` quietly did nothing | An `OrderRefundPending` with no payment row is an upstream data problem; the order then sits in REFUND_PENDING until `OrdersStuckConfirmed` fires — and the operator finds **zero log lines** explaining why no refund ever happened | Rewrote the chain as guard clauses: missing row → `log.warn` ("needs a human"), non-CAPTURED → `log.info` with the actual status (REFUNDED = normal duplicate; FAILED = upstream state-machine violation). Behaviour unchanged, triage no longer blind |

Walk 42 lesson: **never invent an amount of money, and never no-op in silence.** A fallback price turns a config gap into a wrong charge; an unlogged early-return turns a data bug into a mystery. Fail loudly through the paths that already exist (decline → cancel → release; warn → runbook). (Verified in CI — new test + existing 50+ suite.)

## Walk 43: the cancel/charge race that ate the customer's money (Nov 8, 2026)

Scope: `OrderService` saga handlers, fresh eyes. Found the best bug of this block — a **choreography race with real money on the line**.

The race: an order is CONFIRMED and `OrderConfirmed` is on the topic. payment-service will charge it *whenever it gets there* — with consumer lag or a pod restart that's minutes, not milliseconds. In that window the customer cancels: `cancel()` sees CONFIRMED (cancellable — no money moved *yet*), releases the stock, saves CANCELLED. Then the capture lands: `onPaymentCaptured` was `filter(Order::isConfirmed)` → CANCELLED fails the filter → **silent no-op**. payment-service ignores `OrderCancelled` (not its event). Net: **money captured, order CANCELLED, no refund ever triggered, nothing logged.** The payment row sits CAPTURED forever; the customer's card was charged for an order the UI shows as cancelled.

| Found | Why it mattered | Fix |
|---|---|---|
| `PaymentCaptured` arriving for a CANCELLED order was silently dropped | The only orders that can hit this are exactly the cancel/charge race victims (FAILED/PENDING orders never emit `OrderConfirmed`; payment-failure cancellations have no capture coming) — so every occurrence is a customer owed money | `onPaymentCaptured` now branches: CONFIRMED → PAID (as before); **CANCELLED → `requestRefund()` + `saveWithEvent`** — the order re-enters the normal refund saga (`OrderRefundPending` event → payment-service finds the CAPTURED payment → refunds → `PaymentRefunded` → REFUNDED). Stock was already released by `cancel()`; the release in `onPaymentRefunded` is idempotent, so the replay is harmless. Counter `orders.cancel.charge.race` + warn log. New test `lateCaptureAfterCancelTriggersARefundInsteadOfLosingTheMoney` drives cancel-then-capture end to end to REFUNDED |

Design note: the compensation **reuses the existing machinery** — no new states, no new events, no payment-service change. CANCELLED → REFUND_PENDING is semantically honest ("money must come back"), and every downstream handler was already status-guarded, so the re-entry is safe. The alternative (payment-service tracking `OrderCancelled` to skip the charge) trades one race for a harder ordering problem; charging then compensating is the saga answer.

Walk 43 lesson: **in choreography, every "it can't be in that state" IS a state — at-least-once delivery plus user actions means any event can meet any status.** A status filter that silently drops an event with money attached is a bug; the question to ask of every handler is "what does this do when the event arrives *late*?"

## Walk 44: the CI that failed because one byte went missing (Nov 8, 2026)

Scope: Walk 42's `CI` run failed — in **kubeconform**, a job Walk 42 didn't touch. The log: `tar: Error is not recoverable`. Root cause: every tool install in both workflows and the Jenkinsfile was `curl -sSL <url> | tar xz` — no `-f` (an HTTP error page pipes straight into tar), no `--retry`, and a retry can't help mid-pipe anyway because tar has already consumed the truncated stream.

| Found | Why it mattered | Fix |
|---|---|---|
| 6 `curl -sSL … \| tar xz` tool downloads (ci.yml ×1, shopflow.yml ×4, Jenkinsfile ×2 commands) | One flaky byte from GitHub releases fails the whole validate job — a red build with zero relation to the commit, which trains people to ignore red builds | Download to a file with `curl -fsSL --retry 3 --retry-delay 5 -o <file>`, then `tar xzf` the complete file. `-f` fails on HTTP errors (instead of extracting an error page), `--retry` restarts the download cleanly into the file, and tar only ever sees a finished archive |

Walk 44 lesson: **`curl | tar` couples two failure domains — transfer and extraction — so neither can recover.** Download-then-extract is the same line count and survives the blip. And: a CI failure on files you didn't touch is still your bug to chase — this one was a landmine for every future commit.

**Addendum (same day, two humbling follow-ups):** (1) the fix itself failed `yamllint` — the new one-line curls were >160 chars (the repo's line-length limit); broken with `\` continuations. Run the same linter CI runs before pushing, even for a "trivial" workflow edit. (2) The very same run flaked **3 of 4** image jobs at `anchore/sbom-action` with `failed to install syft` — the action's own tool download has no retry, i.e. the exact failure class this walk was fixing, one layer up. Replaced the action with a pinned direct download (`syft v1.57.0`, `curl --retry` + `tar xzf`, `actions/upload-artifact` keeps the same artifact name). Confirmation of the diagnosis: kubeconform (Walk 42's run) and syft flaked in the same hour — a GitHub-releases bad hour takes out every unretried download in CI at once. Meanwhile the **Unit + integration tests job passed** on that run, so Walks 42/43's money fixes were verified even while the lint/SBOM steps flailed.

## Walk 45: the idempotency race the pre-check can't win (Nov 8, 2026)

Scope: `placeOrder`'s Idempotency-Key path. The sequential case is covered (pre-check `findByIdempotencyKey` → replay, test `idempotencyKeyPreventsDuplicateOrders`). But two **concurrent** requests with the same key — a double click, both landing before either commits — both pass the pre-check, both `save()`, and the unique index on `idempotency_key` fails one of them. That `DataIntegrityViolationException` fell through to the global handler as a **409** — while inventory-service's reservation race (same shape, same repo) already does the right thing: the loser returns the **winner's** result.

| Found | Why it mattered | Fix |
|---|---|---|
| Same-key concurrent POST: loser got a 409 error instead of the winner's order | The whole point of an Idempotency-Key is that the client can fire-and-retry blindly; a 409 on a race makes the client handle an error for doing exactly what the key promises to make safe. And the repo was *inconsistent with itself* — inventory already catches the race and returns the winner (201) | `placeOrder` catches `DataIntegrityViolationException`: re-fetch by key → found + owned → return the winner as a replay (200, counter `orders.idempotency.race`); found but another customer's → `IdempotencyKeyConflictException` (the real 409); not found → rethrow (a different constraint, don't swallow). Invariant test `concurrentRequestsWithTheSameIdempotencyKeyCreateOnlyOneOrder`: two threads on a barrier, same key — both must get the **same order id**, no errors, exactly one reservation call |

Note the layering: the **pre-check is an optimization, the constraint is the guarantee, the catch is the recovery** — all three are needed. (The race loser can see the winner's order still `PENDING` if it's mid-flight; that's the same contract as polling `GET /orders/{id}`.)

Walk 45 lesson: **a read-then-write uniqueness check is always a race; the DB constraint is the real guard, so code must expect the constraint to fire and recover, not 500/409.** When one service in the repo already solves a shape correctly (reservation race), every other instance of that shape should match it — self-inconsistency is where reviewers (and interviewers) dig.

## Walk 47: the load test that would fail itself for working correctly (Nov 8, 2026)

Scope: `loadtest/flash-sale.js`, the one file this block hadn't reviewed. Its threshold comment says "5xx are failures; 409/429 are not" — but k6's `http_req_failed` counts **every 4xx** as a failure unless you override the expected statuses, and no `setResponseCallback` existed anywhere in the script.

| Found | Why it mattered | Fix |
|---|---|---|
| `http_req_failed: ['rate<0.01']` with k6's default failure definition | The flash sale **by design** produces 409s (297 of 300 buyers must lose — stock is 3) wait, no: losers get 201+REJECTED via the API's design; but the rate limiter (50/s/pod) throttling the 300-VU burst produces **429s**, and those are k6-"failed" by default. The run would breach its own 1% threshold *because the rate limiter worked* — a gate that fails on correct behaviour trains people to delete the gate | `http.setResponseCallback(http.expectedStatuses({min:200,max:399}, 409, 429))` — now "failed" means what the comment always claimed: 5xx and transport errors. (409 included for safety: the API returns conflict for double-cancel etc.) |

Honest verification note: no Docker locally and CI only *compiles* the k6 script, so this is verified by k6's documented semantics + the compile job; the first real `k6 run` will confirm. The bug class is the same as Walk 36/40 — **a claim in a comment that the code never implemented** — just in the test layer this time.

Walk 47 lesson: **k6's `http_req_failed` is opinionated: 4xx = failure unless you say otherwise.** Any API that deliberately returns 409/429 under load needs `expectedStatuses`, or the load test fails precisely when the system behaves. Comments that describe intended behaviour are claims to verify, not documentation to trust.

## Walk 48: the deploy that checked out a branch that doesn't exist (Nov 8, 2026)

Scope: `infra/ansible/` (the one-VM compose deploy), the last unreviewed corner. First task: `ansible.builtin.git` with `repo_version: main`. But `git ls-remote --heads origin` shows the repository has **only `master`** — `main` has never existed. So `make ansible-deploy` dies at its very first task, on every host, always.

| Found | Why it mattered | Fix |
|---|---|---|
| `group_vars/shopflow.yml` pinned `repo_version: main` for a repo whose only branch is `master` | The whole playbook is unrunnable — and `make validate`'s `ansible-playbook --syntax-check` can never catch it, because a wrong *value* is syntactically perfect. A "validated" deploy that has never actually been run end-to-end | `repo_version: master` with a comment. (The GitHub workflow's main-only gating on push/sign/deploy is a *different, documented* thing — those steps are deliberately never reached; the Ansible path was just wrong) |

The rest of the role reviewed clean: template→systemd unit→started, readiness poll (`uri` + `retries: 30/delay: 10` — module failure retries under `until`), handler restarts on checkout/unit change.

Walk 48 lesson: **syntax checks validate shape, never truth.** A branch name, a metric name (Walk 36's story), a URL — all look identical to the linter whether right or wrong. Anything that names an external thing needs one real execution, or at least a check against the real system (`git ls-remote` here), before "validated" means anything.

## Walk 49: the helm edit that rebuilt four images (Nov 8, 2026)

Scope: the Dockerfiles + build context. Both Dockerfiles reviewed clean (multi-stage, BuildKit `.m2` cache mount, layered jar extraction, fixed non-root UID 10001, `MaxRAMPercentage=75` + `ExitOnOutOfMemoryError`; the distroless variant keeps parity and documents its preStop trade-off honestly). The find was next to them:

| Found | Why it mattered | Fix |
|---|---|---|
| `shopflow/.dockerignore` excluded k8s/, monitoring/, *.md — but **not `helm/` and `loadtest/`** | The CI build uses `cache-from/cache-to: type=gha`, and stage 1 is `COPY . .` — so the cache key includes the whole build context. An edit to a helm values file or the k6 script (never inputs to the image) invalidated the COPY layer and re-ran `mvn package` for **all four services** on the next CI run | Added `helm/` and `loadtest/` to `.dockerignore`. (Deliberately did NOT add `.trivyignore`/`.hadolint.yaml` — they live at the repo root, outside the `shopflow/` build context; dead ignore entries are their own kind of doc-rot) |

Walk 49 lesson: **with `COPY . .` + a remote layer cache, your `.dockerignore` IS your cache key.** Everything in the context that isn't an input to the image is a false rebuild trigger. The checklist: for each top-level path in the context, ask "does the image change if this changes?" — if no, ignore it.

## Walk 50: the supply chain that supplies nothing (Nov 8, 2026)

Scope: follow Walk 48's thread ("names an external thing → check the real system"). The cosign/Kyverno supply-chain loop reads beautifully: CI signs images keyless with the workflow's OIDC identity, and the cluster policy `verify-image-signature` rejects pods whose image isn't signed by exactly `…/shopflow.yml@refs/heads/main`. One problem, verified with `git ls-remote`: **`main` does not exist.**

| Found | Why it mattered | Fix |
|---|---|---|
| GHCR login/push, cosign install/sign, the ECR steps and the prod-overlay pin job are ALL `refs/heads/main`-gated (8 `if:`s), and the repo's only branch is `master` | The publish/sign half of the pipeline has **never run**: no `ghcr.io/rajji01/shopflow-*` image exists, nothing was ever signed, the prod overlay points at images that were never produced, and applying the Kyverno policy today would reject every pod. Meanwhile STATE §3 said "signing/SBOM on `main`" — implying it happens — and even mis-filed SBOM, which actually runs on every build | **Docs now tell the truth** (this is the deliverable): STATE §3 row rewritten (SBOM = every build; publish/sign/pin = gated on a branch that doesn't exist), STATE §4 open item with the actual decision for Abhishek — (a) gate on `master` and start really publishing (public GHCR + signing + flip the Kyverno keyless `subject` to `@refs/heads/master`), or (b) keep publishing off and call the prod overlay design-only. Policy file carries the same NOTE. **Not changed:** the gate itself — turning on public image publishing is an outward-facing decision, not a self-walk's |

Walk 50 lesson: **a security control that has never fired is indistinguishable from a broken one — and docs that describe the design instead of the state are how that hides.** Walk 48 found one dead branch reference; following the same thread found that the whole publish/sign/verify chain hangs off it. "Does this identity exist? has this ever actually run once?" belongs in every supply-chain review.

## Walk 51: the canary gate that graded the whole class (Nov 8, 2026)

Scope: Argo Rollouts `AnalysisTemplate` — the Walk-48/50 thread again ("names an external thing: verify against the real system"), this time the label semantics. The gate's query filtered `rollouts_pod_template_hash!=""`, with a comment claiming "only looks at canary pods".

| Found | Why it mattered | Fix |
|---|---|---|
| `rollouts-pod-template-hash` is the label Rollouts puts on **every** pod it manages — the STABLE ReplicaSet's pods carry it too (it's how Rollouts selects both RSes, like `pod-template-hash` on Deployments). `!=""` therefore matched stable+canary together | The gate measured the **blended** error ratio. At `setWeight: 10`, a canary serving **15% errors** moves the blend to only ~1.5% — under the 2% threshold — and the broken canary gets promoted step by step. The gate is weakest exactly when it matters most (small canary weight) | The template now takes a `canary-hash` arg and filters `rollouts_pod_template_hash="{{args.canary-hash}}"`; both Rollouts pass it via `valueFrom: podTemplateHashValue: Latest` (canary RS in the canary strategy; the GREEN/preview RS in blue-green's `prePromotionAnalysis` — same "newest pods" semantics). Honest dependency noted in the template: the cluster's Prometheus must labelmap pod labels for `rollouts_pod_template_hash` to exist on app metrics at all (the repo's compose Prometheus uses static targets; the k8s scrape config is the lab cluster's) |

Walk 51 lesson: **a canary gate must divide by the canary's own traffic, not the service's.** Any blended ratio dilutes the signal by the canary's weight — the smaller (safer) your first step, the blinder the gate. And the recurring theme of this whole block: the comment said "canary only"; the label semantics said otherwise — claims about external systems (labels, branches, metrics, statuses) are verified, not trusted. (CI validates both manifests against the Rollouts CRD schemas; a live-cluster run is the labs' part.)

## Walk 52: the Slack message with a literal "\n" in it (Nov 8, 2026)

Scope: `alertmanager.yml`. Routing verified correct (page → PagerDuty + #shopflow-incidents via `continue: true`; ticket → #shopflow-alerts at 24h repeat; both severities in use are routed; ServiceDown and fast-burn inhibits sane). Two small real defects in the receivers:

| Found | Why it mattered | Fix |
|---|---|---|
| `slack-incidents`' `text` was **single-quoted** YAML containing `\n` | Single-quoted YAML keeps `\n` as two literal characters, so the page-channel message rendered `…summary\nRunbook: …` on one line with a visible `\n` — cosmetic, but it's the message someone reads at 3 AM | Double-quoted the string (YAML double quotes interpret `\n` as a real newline); verified by parsing the YAML and checking `chr(10)` is in the loaded value |
| The ticket channel (`slack-alerts`) got **no runbook link at all** | Every ticket alert we've added (PaymentRefundFailed, KafkaMessageDeadLettered…) exists *because* a human must follow a runbook — and the ticket notification didn't carry it | Same summary+runbook template as the incidents channel |

Walk 52 lesson: **YAML quoting is semantics, not style — `'\n'` and `"\n"` are different strings.** And an alert's notification must carry the runbook link in *every* channel it lands in; the ticket queue is exactly where the runbook gets followed.

**Addendum (Walk 51's own yamllint own-goal, caught by CI):** the new canary-hash label filter pushed the two PromQL lines to 179/164 chars > the repo's 160 limit — the *same* mistake as Walk 44's addendum, proving that lesson wasn't yet a habit. Fixed by breaking the selector across lines (PromQL is whitespace-insensitive inside `{}`), and `yamllint` is now installed locally so every YAML edit gets linted *before* the push (local CRLF `new-lines` noise excluded — git autocrlf artifact; CI checkouts are LF). Everything else on that run was green: tests, k6 compile, all 4 images + SBOM.

## Walk 53: the reconciler that treated "someone beat me to it" as an error (Nov 8, 2026)

Scope: `OrderReconciler` under the deployment's real shape — HPA `minReplicas: 2` means **every pod** runs `reconcileFailedOrders` every 60s over the **same** stale rows (`findTop100…` has no `FOR UPDATE SKIP LOCKED`, unlike the outbox). And the customer can cancel the same order at the same moment. So the `@Version` race on the cancel+save isn't hypothetical — it's the steady state.

| Found | Why it mattered | Fix |
|---|---|---|
| The reconcile loop caught only `RestClientException`/`CallNotPermittedException`; a race loser's `OptimisticLockingFailureException` **escaped** | "Another pod/customer already settled this order" is a **success**, but it produced an ERROR stacktrace, aborted the **rest of the batch** for that tick, and skipped that tick's `stuckConfirmed` gauge update — the number `OrdersStuckConfirmed` alerts on. A normal race degraded both settlement throughput and the alert signal | 1) The loop catches `OptimisticLockingFailureException` → `log.info` + continue (the double inventory release was already idempotent). 2) `OrderReconciler.run()` wraps reconcile in `try/finally` so the gauge refreshes even when a pass fails. 3) Race test `reconcilerSurvivesAnOrderSettledConcurrently`: a WireMock scenario makes the reconciler's release slow and the customer's cancel fast, so the reconciler loses the `@Version` race deterministically — asserts the reconciler completes normally, the customer gets a definitive 200/409, and the order is settled exactly once |

Design note: the heavier alternatives — ShedLock/leader election, or `SKIP LOCKED` on the query — buy less here than they cost: the work is idempotent and `@Version` already guarantees exactly-once settlement; the only real defect was treating the expected race as an exception. (Same lesson as Walk 43, one layer down: **every "can't happen" concurrent outcome is a state your code must have a normal path for.**)

Walk 53 lesson: **catch clauses encode your concurrency model.** The loop knew inventory could be down but not that a sibling pod exists — yet the deployment guarantees one. Read every `catch` list against the real topology: N replicas, retries, and users acting mid-flight.

## Walk 54: the stampede fix the notes taught and the code skipped (Nov 8, 2026)

Scope: the cache-aside path. `interview-notes/11` teaches `sync = true` twice — "coalesce concurrent misses… stampede protection" and in the thundering-herd fix list — but the project's only `@Cacheable` (`ProductController.get`) didn't use it. The repo's recurring bug-pattern, fourth appearance: a claim the code never implemented.

| Found | Why it mattered | Fix |
|---|---|---|
| `@Cacheable(cacheNames="products", key="#sku")` without `sync` | The products cache has a **60s TTL** (the Walk-46-verified safety net) — so a hot product's key expires every minute **by design**. During a flash sale that means: every minute, all concurrent readers of that sku miss together and hit Postgres at once. The browse load (k6: 200 req/s) funnels into a 1-minute-period DB spike train | `sync = true`: concurrent misses for the same key coalesce into one DB load per JVM (others wait for the loaded value); cross-pod it's still at most one query per pod per expiry — spike train becomes a trickle. Constraints checked: `sync` allows a single cache name (we have one) and no `unless` (we have none). Note synced into interview-notes/11 |

Walk 54 lesson: **a TTL safety net turns every hot key into a scheduled stampede unless misses coalesce.** `sync = true` is one word; the checklist is TTL × hotness × concurrency. And when your own notes teach a fix, grep your own code for it — teaching it and shipping it are different claims.

## Walk 55: "release is idempotent" — but only if nobody else is releasing (Nov 8, 2026)

Scope: inventory-service's reservation path, read against the same topology lens as Walk 53. Order-service leans on "release is idempotent" in at least four places (cancel, onPaymentFailed, onPaymentRefunded, the reconciler — Walk 43's comment even says "the double release is safe"). Checked whether that's true **concurrently**: `Reservation` has **no `@Version`**, and `release()` guarded the stock increment with a plain read.

| Found | Why it mattered | Fix |
|---|---|---|
| `release()` = `findByOrderRef` → filter `RESERVED` → in-memory `r.release()` + `incrementStock(+qty)`. Two concurrent releases (reconciler on two pods — HPA `minReplicas: 2` runs it on the same stale rows every 60s — or a customer cancel racing the reconciler, i.e. exactly Walk 53's scenario) **both** read the committed `RESERVED` row, both pass the filter, both increment | Without `@Version` the two status writes just last-writer-win — no exception, nothing in the logs — and the stock comes back **twice**. Phantom stock: the oversell guard (`decrementStock`'s conditional UPDATE) faithfully protects a number that is now wrong, so the service happily sells units that don't exist. Walk 53 explicitly relied on "the double release was already idempotent" — true sequentially, false concurrently | The status flip is now the guard, using the repo's own oversell pattern one entity over: `ReservationRepository.transition(orderRef, RESERVED, RELEASED)` — a conditional `UPDATE … WHERE status = :from` whose row lock serialises the racers, so exactly one caller gets `1` back and only that caller runs `incrementStock`. The loser logs "released concurrently, skipping". The entity's in-memory `release()` mutator is deleted (with a NOTE why), so the trap can't be reintroduced. Test `concurrentReleasesRestoreStockOnlyOnce`: CyclicBarrier, two threads release the same order — before the fix stock came back `before + 3` |

Design note: `@Version` on `Reservation` was the alternative — but it needs a schema change, turns the loser into an `OptimisticLockingFailureException` → HTTP 500 that order-service would misread as "inventory down" (breaking the reconciler's batch, Walk 53 all over again at the other end of the wire), and still needs a catch to mean "fine, skip". The conditional UPDATE needs none of that and matches `decrementStock`, which this same service already trusts for the much scarier oversell race.

Walk 55 lesson: **"idempotent" has two tiers — replay-idempotent (same call again later) and race-idempotent (same call at the same time) — and a status-guarded read-then-write only buys the first.** Every place a comment says "X is idempotent so the double call is safe", ask: safe when the two calls *overlap*? If the guard is a read, it isn't.

## Walk 56: the auth layer, verified clean (Nov 8, 2026)

Scope: the whole order-service security stack, adversarially — `SecurityConfig`, `JwtDecoderConfig`, `JwtRolesConverter` — plus every external name it depends on (pattern #2: verify against the real system).

Checked, with the result:

- **Matchers**: `/actuator/**` permitAll is backed by a tight exposure list — all four services expose only `health,info,prometheus` (no env/heapdump/beans to leak). Swagger permitAll → springdoc 2.8.17 really is on the classpath. `GET` orders = customer|support, writes = customer, `anyRequest().denyAll()` — deny-by-default holds.
- **Role names vs the real IdPs**: `hasRole("customer"/"support")` matches `shopflow-realm.json` realm roles (`customer`, `support`) AND `cognito.tf`'s `aws_cognito_user_group.roles` keys exactly. No case drift, no "customers".
- **Decoder**: lazy `SupplierJwtDecoder` (pod starts with IdP down), default validators (sig/JWKS/exp/iss) + audience pinned via `aud`/`azp`/`client_id` when `JWT_AUDIENCE` set — `AudienceValidatorTest` covers it; empty-audience dev mode is documented in the yml.
- **Known gap is documented, not hidden**: inventory/notification/payment have no app-level auth by design (NetworkPolicy zero-trust; interview-notes/01 lists client-credentials/mTLS as the next step). That stays a documented decision, not a finding.

Nothing to change. **Verified clean.**

Walk 56 lesson: an auth review is mostly name-matching across systems — matcher paths vs exposed endpoints, `hasRole` strings vs the IdP's actual role/group names, audience value vs what the IdP really puts in `aud`/`azp`/`client_id`. Every one of those pairs is a place where two files can silently disagree; here all of them agreed.

## Walk 57: the schema only H2 ever validated (Nov 8, 2026)

Scope: Flyway migrations vs the entities, adversarially. The migrations themselves audit clean — every query's index exists (`idx_orders_status_updated` for the reconciler, `idx_orders_customer_created` for the list, unique keys for every `findBy...`), the outbox index matches the relay's "oldest unpublished first", and V3 even documents expand-contract. But chasing one type detail exposed a verification hole bigger than any single column:

| Found | Why it mattered | Fix |
|---|---|---|
| All entities store time as `Instant`; all migrations declare plain `timestamp`; every service runs `ddl-auto: validate`. Hibernate 6 maps `Instant` to `TIMESTAMP_UTC` (= `timestamptz` on Postgres) by default — a [known](https://lists.jboss.org/archives/list/hibernate-issues@lists.jboss.org/thread/Y7MGNXH3HT6D3ROFQFUJJ3PRPCVSND6B) [source](https://lists.jboss.org/archives/list/hibernate-issues@lists.jboss.org/2023/1/?page=68) of startup-time validation failures. Our 51 tests all validate against **H2** — the engine production doesn't use. **No Postgres has ever validated this schema** anywhere in CI; compose/k8s runs are exactly where `validate` would explode, at startup | If Postgres rejects what H2 forgave, every service fails to boot in compose and k8s — the worst kind of bug: invisible to all 51 green tests, fatal on the first real deploy | New CI job `postgres-smoke` (shopflow.yml): a real `postgres:16-alpine` service container, one database per service, and a `PostgresSchemaValidationTest` per service (`@ActiveProfiles("pg")`, gated by `PG_SMOKE=true` so it is skipped locally and in the H2 test job) that boots the **full Spring context** — Flyway migrates real Postgres, Hibernate validates against it. Kafka absent is fine: listener containers retry in the background and don't block startup. Verdict (does Postgres accept `Instant` vs `timestamp`?) comes from the job's first run — recorded in the addendum below |

Walk 57 lesson: **"the tests are green" only certifies the engines the tests run on.** Every H2-for-speed test suite has a set of claims it structurally cannot check — type strictness, locking semantics, SQL dialect edges — and `ddl-auto: validate`'s whole job happens at startup against the real engine. A one-test smoke job against the production database engine is cheap; discovering the mismatch during a deploy is not.

## Walk 58: the third place that says "main" (Nov 8, 2026)

Scope: Terraform — IRSA least-privilege (`irsa.tf`) and the CI OIDC role (`ci-oidc.tf`), the last unreviewed corner of `infra/`.

What held up under the adversarial pass (worth saying, because each is a classic failure elsewhere): the kafka-clients policy grants exactly produce/consume/auto-create on `orders.events*`/`payments.events*` (the `*` suffix covers the `.DLT` topics; the Walk-19 DeleteTopic trap stayed fixed), its topic/group ARNs build from the same `var.name` the MSK `cluster_name` uses, so they cannot drift apart; inventory-service is correctly absent (it has no Kafka); the ExternalSecret's `shopflow/prod/db` key and its four `property:` names match `rds.tf`'s secret name and JSON keys exactly; the ESO role reads only that one secret ARN; ECR is per-repo scoped with only `GetAuthorizationToken` on `*` (it supports no resource scoping).

| Found | Why it mattered | Fix |
|---|---|---|
| `ci-oidc.tf`'s trust policy pins `sub` to `repo:...:ref:refs/heads/main` — the branch Walk 50 proved doesn't exist. That makes **three** independent places gating publishing on `main`: the workflow `if:`s, the Kyverno keyless `subject`, and now the AWS role trust | The decision in STATE §4 said "flip the gates and the Kyverno subject" — anyone doing exactly that would still watch CI's `Assume AWS role` step die with an opaque `Not authorized to perform sts:AssumeRoleWithWebIdentity` (sub mismatch), because the third copy of the branch name lives in Terraform, not the repo's YAML | NOTE in `ci-oidc.tf` + STATE §4 updated: the publishing decision now lists all three gates that must flip together. Also fixed the policy comment lying about "three ECR repositories" (there are four — one per service) |

Walk 58 lesson: **a "gate" that exists in N systems is N copies of the same constant, and they only LOOK like one switch.** When a branch/identity/name change is on the table, grep every repo *and* the IaC for the literal — the copy you miss fails at the farthest point from where you flipped, with the least helpful error.

## Walk 59: dedupe memory shorter than Kafka's actual memory (Nov 8, 2026)

Scope: notification-service's listener + the `processed_event` cleanup jobs — specifically the comment's claim: *"keep as long as the topic's retention (7d): a redelivery older than that cannot happen anymore."* Verified against how Kafka actually deletes data, and it's false twice over.

| Found | Why it mattered | Fix |
|---|---|---|
| Both `ProcessedEventCleanup`s (notification-service, order-service) deleted dedupe rows after **7d** — exactly equal to MSK's `log.retention.hours=168`. But (1) retention deletes a **segment** only when its *newest* record is past retention, so on a quiet partition a record survives up to retention + segment-roll (`log.roll.hours` default is another 168h ≈ 7d) — the oldest *replayable* record is ~14 days old, not 7; (2) both consumers run `auto-offset-reset: earliest` — chosen precisely so a reset/expired group **replays the topic** | On any replay (group reset for reprocessing, offsets expired after an outage, a new environment pointed at the same cluster), the tail of the replay — events 7–14 days old — has **no dedupe rows left**: notification-service re-sends customer emails, order-service re-processes `PaymentCaptured`/`PaymentRefunded` with only the status guards as the last line. Zero safety margin, by exact arithmetic coincidence of two unrelated 7s | `processed-events.retention` default **7d → 14d** in both services (= the real replayable window; rows are ~40 bytes, the margin is free), and the false comments rewritten in both repositories + cleanups to state the actual rule. interview-notes/01+10 synced. The `outbox.cleanup.retention: 7d` is untouched on purpose — published outbox rows are producer-side debugging history, not a dedupe guarantee |

Walk 59 lesson: **"retention = 7d" is when Kafka is *allowed* to delete, not when data is gone — the replayable window is retention + segment roll, and your idempotency store must outlive THAT.** Two independent defaults that happen to be equal is not a margin, it's a coincidence waiting for a replay. (And the recurring pattern again: a comment making a claim about an external system — "cannot happen anymore" — that the external system's semantics don't back.)

## Walk 60: teach-back — Walks 55 & 59 as notes + diagrams (Nov 8, 2026)

Docs-only walk (the ritual's other half): the two new concepts earned their EASY-NOTES + VISUAL-FLOWS places.

- **EASY-NOTES §2a** — "Idempotent ke do tier: replay vs race": read-guard sirf replay rokta hai; race mein atomic conditional UPDATE chahiye. Side-by-side timeline, `transition()` fix, test ka naam.
- **EASY-NOTES §10a** — "Retention ≠ data gaya": replayable window = retention + segment-roll; ASCII timeline dikhata hai 7d dedupe ka "BHUL GAYA" gap aur 14d fix.
- **VISUAL-FLOWS #17** — double-release race ke do mermaid sequence diagrams: bug wala interleaving (phantom stock +6) vs fix wala (row lock + WHERE re-check → 0 rows → skip), with the @Version-vs-conditional-UPDATE design note.

No code changed.

## Walk 61: the resilience stack, verified clean (Nov 8, 2026)

Scope: `InventoryClient` + the whole `resilience4j:` block, adversarially, against the known trap list:

- **Aspect order**: the comment claims `Retry(CircuitBreaker(call))` — matches resilience4j-spring-boot3's real default order (Retry outermost), so the breaker records every attempt's true outcome and an open breaker is NOT retried (`CallNotPermittedException` isn't in the retry whitelist — a whitelist means everything else fails fast). ✓
- **Retry whitelist** (`ResourceAccessException`, `HttpServerErrorException`): transient-only; business 409/404 become `InventoryRejectedException` before the retry aspect sees them, and the CB `ignore-exceptions` the same type. Lost-response retry is safe because reserve is idempotent by orderRef (and since Walk 55, release is even race-idempotent). ✓
- **Budget math**: worst case ≈ 3 × 2s reads + jittered backoffs ≈ 7s inside `placeOrder` — bounded, and the breaker (window 10, min 5, 50%) opens after ~2 outage-hit user calls because each call records 3 attempt-failures. Deliberate fast-open, documented in notes. ✓
- **Compose startup ordering**: services `depends_on` postgres/kafka with `condition: service_healthy` + own healthchecks + `restart: unless-stopped`. ✓

Nothing to change. **Verified clean** — Walks 39/42/47 already closed this area's real gaps; today's pass confirms no regression and no next-layer trap.

Walk 61 lesson: a resilience review is four questions — what wraps what, what exactly gets retried, what does the breaker count vs ignore, and what's the worst-case latency budget. If all four have deliberate answers in config + comments that match the library's real semantics, the stack is done; don't invent a fifth knob to turn.

## Walk 62: every dashboard query, name-checked (Nov 8, 2026)

Scope: `shopflow-red.json` — all 16 panel expressions extracted and verified against what actually exists (pattern #2's favourite hiding place: a dashboard quietly graphing a metric nobody emits).

Verified: the 5 `service:`/`sli:` recording-rule names all exist in `recording-rules.yml`; `orders_total`/`payments_total`/`payments_refund_failed_total`/`kafka_dead_lettered_total`/`orders_stuck_confirmed`/`outbox_unpublished` all match their Micrometer registrations (dot→underscore + `_total` for counters); `kafka_consumer_fetch_manager_records_lag_max`, `resilience4j_circuitbreaker_not_permitted_calls_total` and the two Hikari gauges are standard binder names. **No orphan queries, no change.**

Walk 62 lesson: a dashboard is a pile of claims about metric names, and nothing validates it — promtool checks rules, kubeconform checks manifests, but a Grafana JSON panel pointing at a typo'd metric renders a perfectly healthy empty graph. Extract-and-grep is the whole audit, and it's five minutes.

**Walk 57 addendum — the verdict (run on `dbfc4f4`):** the `postgres-smoke` job is GREEN, non-vacuously: the logs show `Tests run: 1, Failures: 0` per service with profile `pg`, each context taking 6–10s to start (real Flyway migrations + `ddl-auto: validate` against Postgres 16). So the researched hazard (Hibernate 6 `Instant` → `TIMESTAMP_UTC` vs our plain `timestamp` columns) does **not** bite this Hibernate 6.6 / Boot 3.5 combination — the hypothesis is dead, and that's fine: the walk's deliverable was never the bug, it was the missing verification. "The schema works on the engine production uses" is now a CI-checked fact on every push instead of an assumption, and any future type drift (a new `timestamptz` column, a UUID/text mismatch, a seed that only H2 accepts) fails in CI instead of at deploy. Honest failures of a hypothesis get recorded too — that's what keeps the rest of this file trustworthy.

## Walk 63: Order entity + controller edges, verified clean (Nov 8, 2026)

Scope: `Order.java` (state machine) + `OrderController` — hunting the edges Walks 43/45/53 didn't already close.

- **Transitions**: entity setters are deliberately dumb; every caller in `OrderService` is status-guarded and `@Version` catches concurrent writers (the Walk 53/55 lesson applied where it was already right). `isCancellable()` = CONFIRMED|FAILED only — PENDING (in flight) and terminal states refuse, PAID detours to the refund saga.
- **Oversized `Idempotency-Key`** (the Walk 45 catch's blind spot I went hunting for): a >100-char key would hit the `varchar(100)` column as `DataIntegrityViolationException`, where the race-recovery would find no winner and rethrow → 500 for a client mistake. But the chain holds: `@Size(max = 100)` on the `@RequestHeader` + Spring 6.1+ native method validation → `HandlerMethodValidationException` → the advice's 400 with field messages. The DB never sees the long key.
- **Pagination**: `size ≤ 100`, sort `createdAt desc` rides `idx_orders_customer_created`. Replay semantics: 201+Location on create, 200 on idempotent replay — correct REST shape.

Nothing to change. **Verified clean.**

Walk 63 lesson: when a recovery path exists (the Walk-45 constraint catch), ask what ELSE can throw the same exception type — here the length overflow — and check each alternate cause has its own guard upstream. An exception type is a bucket, not a cause.

## Walk 64: `make lint` wasn't what CI runs (Nov 8, 2026)

Scope: CLAUDE.md §4's promise — "verify karo: jo CI chalata hai wahi (`make lint/validate/test`)" — diffed target by target against the two workflows. The promise had drifted in four places:

| Found | Why it mattered | Fix |
|---|---|---|
| `hadolint` ran without `-c .hadolint.yaml`; `gitleaks` without `-c .gitleaks.toml` | The configs exist precisely to carry triaged ignores/allowlists — locally you'd get false failures CI doesn't have (or, worse, learn to ignore `make lint` failing) | Both now pass the same configs CI uses |
| `make validate`'s `kyverno apply` included `verify-image-signature.yaml` | That policy needs a registry + a real signature (which don't exist — Walk 50), so `make validate` **always failed locally** on a step CI deliberately excludes (`grep -v verify-image-signature`). A verification command that always fails is one nobody runs | Same exclusion as CI, with the reason as a comment |
| `ansible-lint` ran in CI's lint job but nowhere in the Makefile | Local "lint green" ≠ CI lint green for any ansible edit | Added to `make lint` |
| CI's new `postgres-smoke` (Walk 57) had no local equivalent | The one check that needs a real engine was CI-only even on machines that have Docker | New `make test-pg`: starts `postgres:16-alpine`, creates the four DBs, runs the `PG_SMOKE` tests, always stops the container, exits with the test status |

Verification note, honestly: this machine has no Docker/ansible/hadolint, so the new recipes are verified **textually** against the exact CI commands+flags (and `make help`/syntax by inspection), not by running them — same standing as the rest of the local-tooling story in STATE §3.

Walk 64 lesson: **a parity promise ("local runs what CI runs") is a contract with no enforcement — it only stays true if someone re-diffs it after every CI change.** Every Walk that touched the workflows (40, 44, 51, 57) owed this file a visit; the debt collected today. The tell was the kyverno step: a local command that *cannot* succeed means nobody has run the target since it broke.

## Walk 65: the connection math three files told three ways (Nov 8, 2026)

Scope: `k8s/base` — the four service manifests diffed against each other (normalized names/ports), then the one claim they all make — the DB connection budget — chased across every file that states it.

The manifests themselves audit clean and tightly parallel: HPA owns replicas (no `spec.replicas`), `maxUnavailable: 0`, startup/liveness/readiness split correctly (readiness = own DB only), preStop sleep + 45s grace, non-root + read-only rootfs + dropped caps + seccomp, topology spread, `automountServiceAccountToken: false`. The only drift between the four deployments is the intended env.

| Found | Why it mattered | Fix |
|---|---|---|
| Three copies of the connection arithmetic, all stale, each differently: deployments said "10 pods (HPA max) × 5 = 100 total" (base HPA max is **6**, and there are **4** services), `postgres/statefulset.yaml` said "2 services × 10 pods" (it's 4 × 6), `rds.tf` said "2 services × 10 × 5 = 100 plus notification-service" (prod is 4 × 10 × 5 = **200**) | The real numbers happen to still fit — base 120 < 200 (in-cluster PG), prod 200 < 300 (RDS parameter group) — but anyone *re-deriving* capacity from these comments (e.g. before raising an HPA max or shrinking RDS) would start from numbers that were never true together. Connection exhaustion is exactly the failure where readiness (`db` check) flaps every pod at once | All three now state the same worst-case formula with both environments' numbers; rds.tf's note also names the transient consumers (Flyway, reconcilers, `superuser_reserved_connections`) that eat into headroom |

Walk 65 lesson: **numbers quoted in comments are the most-copied, least-verified claims in a repo — and the same budget stated in N files is the same constant copied N times** (Walk 58's lesson, arithmetic edition). State the formula, not just the result, and re-run it whenever any factor (service count, HPA max, pool size) changes.

## Walk 66: CORS + the realm's wildcard origin (Nov 8, 2026)

Scope: the browser-facing auth edges nobody had walked — both CORS configs and the Keycloak realm export.

Clean parts: both `CorsConfig`s default to `http://localhost:*` origin patterns only, no `allowCredentials` (Bearer tokens, not cookies), overridable via `APP_CORS_ALLOWED_ORIGIN_PATTERNS`, and their "DEV ONLY" claims match reality (prod fronts services with an ALB and Cognito). Realm: `accessTokenLifespan` 900s, and `directAccessGrantsEnabled: true` is exactly what the docs claim it is (local-testing convenience).

| Found | Why it mattered | Fix |
|---|---|---|
| The `shopflow-web` client had `"webOrigins": ["*"]` while its `redirectUris` were already scoped to `http://localhost:*` | The wildcard tells Keycloak to answer CORS for its token/userinfo endpoints from **any** origin — broader than the client's own redirect scope, and a copy-paste seed: dev realm exports have a way of becoming prod realm imports. Keycloak has a dedicated idiom for "same as my redirect URIs": `"+"` | `webOrigins: ["+"]` — origins now derive from the redirect URIs, so tightening one tightens both. Same file serves compose and k8s (one source, no second copy to drift) |

Walk 66 lesson: `*` in any security-adjacent list is a claim that "anyone" is the right audience — almost always it's a stand-in for "whatever my redirect URIs say", and Keycloak literally spells that `+`. Grep your realm exports for `"*"` before they graduate from dev.

## Walk 67: the complete census of "main" (Nov 8, 2026)

Scope: `argocd/` — the one deploy surface no walk had opened — plus, on finding the problem there, finally doing what Walk 58's lesson prescribed: grep the *whole* repo and IaC for the branch literal instead of finding one copy per walk.

| Found | Why it mattered | Fix |
|---|---|---|
| `application-prod.yaml` has `targetRevision: main` — and the Jenkinsfile gates its `Push images` and `Pin prod overlay` stages on `when { branch 'main' }`. With Walks 50/58 that makes the publish-on-main decision live in **six copies across five systems**: GitHub workflow (8 ifs), Kyverno keyless subject, AWS OIDC trust `sub`, Argo CD Application, Jenkins ×2 | The Argo CD copy is the sharpest: `kubectl apply` this Application today and it errors `revision main not found` — the *prod GitOps entry point* points at a void. And each copy fails differently (skipped job, rejected pod, STS AccessDenied, sync error, skipped stage), so flipping N−1 of them still leaves a broken pipeline with a misleading error at the Nth | NOTE on the Application + the Jenkinsfile stage; **STATE §4 now carries the complete census** so decision (a) is a checklist, not an archaeology project. Gates themselves unchanged — same outward-facing-decision stance as Walks 50/58 |

Argo CD manifests otherwise reviewed clean: dev app is manual-sync on HEAD (inspect-then-apply), prod has prune+selfHeal+ServerSideApply, retry backoff, the HPA `ignoreDifferences` on `/spec/replicas` matching the no-`spec.replicas` deployment choice, and the finalizer documented.

Walk 67 lesson: when you find the second copy of a constant, stop and take the census — the cost of the full grep is minutes, and every copy found later costs a walk (this one took three: 50, 58, 67). "How many systems know this fact?" belongs in the first copy's review.

## Walk 68: the chart that would let you fight your own autoscaler (Nov 8, 2026)

Scope: `helm/shopflow-service` templates + `ingress.yaml` — the Helm half of the deploy story, walked against the Kustomize base it mirrors.

Clean parts: the template is a faithful parameterisation of the base (same probes trio, preStop, non-root + read-only rootfs + dropped caps, topology spread, `automountServiceAccountToken: false`, maxUnavailable 0); values default `replicas: null` with the same "HPA owns the count" comment as the base; ingress exposes only `/api/v1/orders` and `/api/v1/products` (reservations API stays cluster-internal, matching the NetworkPolicy story), keycloak host is dev-only and the prod overlay removes it.

| Found | Why it mattered | Fix |
|---|---|---|
| The chart rendered `replicas:` whenever a user set it — even with `hpa.enabled: true` (the default). The base's own comment explains the failure: a fixed `replicas` resets the count on every `helm upgrade` and fights the autoscaler. The guard was only a comment in values.yaml — nothing enforced it | This is the classic Helm footgun shape: a convention documented next to a default, silently violated by one values override. HPA scales to 6, the next deploy slams it back to 2, repeat — a sawtooth that looks like a capacity mystery | `{{ fail }}` when both `replicas` and `hpa.enabled` are set: the chart now refuses to render the contradiction, with the reason in the error message. CI's `helm lint` + `template \| kubeconform` verify the happy paths still render |

Walk 68 lesson: **in a chart, a convention that lives only in a values comment is not a guard — `fail` is how a chart states its invariants.** Anywhere two values can contradict each other (fixed count vs autoscaler, both TLS modes, replicas vs PDB minAvailable), the template should refuse, not pick silently.

## Walk 69: teach-back — the census rule (Nov 8, 2026)

Docs-only walk: the repo's most-repeated finding class (Walks 48/50/58/65/66/67 — a constant copied across systems, each copy failing differently) graduated into method:

- **EASY-NOTES Part 6, Step 7** — "Ek fact ki N copies gino": the six-copies-of-`main` story as a worked example, the grep-on-second-copy rule, and the two acceptable end-states (derive from one source, or a complete list in the decision doc).
- **Quick map** row: "gate flip kiya, phir bhi pipeline tuta (alag error)" → census rule.

No code changed.

## Walk 70: four alerts nobody ever tested (Nov 8, 2026)

Scope: `alert-rules.yml`, full semantic pass — every expr read against the metrics it names (ServiceDown's `job="shopflow"` matches `prometheus.yml`'s one `job_name` ✓; every metric name verified in Walk 62) and against the test file that is supposed to pin it.

| Found | Why it mattered | Fix |
|---|---|---|
| Of the 10 alerts, four had **no promtool test at all**: `HighP99Latency`, `ServiceDown`, `KafkaConsumerLagHigh`, `PaymentFailureRateHigh` — including two pages | Walk 36's own lesson ("an alert that was never tested is a hope, not an alert") had been applied only to alerts added *since*; the founding four were grandfathered in untested. `HighP99Latency` is exactly the kind that fails silently: one histogram_quantile subtlety (e.g. all mass in `+Inf` returns the highest finite bucket bound — `> 1` would then never fire with 1.0 as the top bucket) and the ticket never comes | 8 new test cases (now 17): p99 fires at ~2.5s / stays silent when fast / **stays silent when only `/actuator` is slow** (the `uri!~` filter now has a test earning its keep), ServiceDown pages with the right instance label, lag tickets at 2000 and not at 500, payment-failure pages at 60% and not at 10% (declined cards are normal) |

Walk 70 lesson: **coverage debt hides at the founding — rules written before the "every alert gets a test" norm existed never got back-filled, and nothing flags them.** The audit is one grep: every `alert:` name must appear in the test file at least twice (a firing case and a silence case). The silence cases are half the value: they pin what must NOT wake a human.

## Walk 71: the double-money windows and the contract that never mentioned them (Nov 9, 2026)

Scope: `OrderConfirmedListener` end-to-end — the money path — against the Walk 53/55 concurrency lens plus the "crash between two effects" lens.

The DB side is tight: charge dedupes by eventId PK (a racing loser hits the PK, rolls back, retries into the exists-check), refund is state-guarded by CAPTURED and every no-op branch logs *why* (Walk 42). But two windows move money **outside** the transaction's protection:

| Found | Why it mattered | Fix |
|---|---|---|
| (1) Crash between `gateway.charge()` and the commit: the row never lands, redelivery re-runs `charge()`. (2) A rebalance can deliver the same event to two consumers: both pass `existsById`, both call the gateway; the DB converges (PK) but the processor saw **two charges**. Same shape for `refund()`. Nothing anywhere said what makes this safe — `PaymentGateway`'s javadoc was one line about swapping implementations | The thing that makes at-least-once + remote money movement safe is **processor-side idempotency keyed on orderRef** (Stripe `Idempotency-Key`, Razorpay `receipt`) — and the person who writes the real adapter learns the requirements from the interface contract. A silent contract here means the first real integration double-charges under exactly the failures Kafka guarantees will happen | The contract now states it: orderRef IS the idempotency key, the adapter MUST send it, with both failure windows named — and why the ordering is deliberate (commit-first-charge-after is worse: a crash then fakes a CAPTURED row for money never moved). Listener javadoc carries the honest caveat. No behavior change: the fake gateway is test-only, and a claim-first "fix" in the listener would trade double-charge for charge-lost |

Walk 71 lesson: **a transaction boundary that contains a remote call has exactly two orderings, and both lose — the only real fix lives on the other side of the wire (idempotency keys), which makes it a CONTRACT requirement, not an implementation detail.** Interfaces that hide a money-safety requirement in the implementor's imagination are how "we retried and charged him twice" post-mortems start.

## Walk 72: Walk 59's blast radius in the notes (Nov 9, 2026)

Docs-only sweep: grepped every doc that mentions `processed_event`/dedupe for leftover "7d" claims after Walk 59's 14d change. Four stale spots — including interview-notes/12 still answering a hard interview question with the exact sentence Walk 59 disproved ("once Kafka retention has passed, nothing older can be redelivered"), and interview-notes/03 claiming 7d was "longer than the topic retention" when it was precisely equal. All four now state 14d + the replayable-window reasoning; the outbox-cleanup 7d mentions stay (deliberate, debugging history) but 10-kafka's "the DB copy is there for as long as Kafka can redeliver" was softened — by Walk 59's own math that claim was 7 days short too.

Walk 72 lesson: a fix's blast radius includes every place the OLD reasoning was taught. STATE's rule covers files that disagree with STATE; the subtler rot is notes that agree with each other and with last month's code. The grep after a semantics-changing walk is part of the walk.

## Walk 73: the prod overlay's two dead-on-arrival details (Nov 9, 2026)

Scope: `k8s/overlays/prod/kustomization.yaml`, semantic read (it had only ever been schema-validated). The structure holds up — managed-service deletes are complete (workload + Service + the NetworkPolicies that referenced them), `$(RDS_ENDPOINT)` is inserted at `env/0` so the `$(VAR)` expansion in DB_URL resolves (k8s expands only previously-defined vars), positional `env/1` patches match the base's env order, and `?sslmode=require` is on every RDS URL. Two things did not hold up:

| Found | Why it mattered | Fix |
|---|---|---|
| **No `JWT_AUDIENCE` anywhere in prod.** The validator treats empty as "don't check" (dev convenience), and D09/interview-notes claim prod pins the audience — pattern #1, a documented posture the config never implemented | In the one environment with a real IdP, any token from the same Cognito pool — minted for a different app client — would pass; the confused-deputy defense existed only in code paths never configured on | `jwt-audience` literal in the endpoints ConfigMap (placeholder in the established `REPLACE-` style, sourced from `terraform output cognito_web_client_id`) + `JWT_AUDIENCE` env on order-service |
| **payment- and notification-service had zero 443 egress** — base allows only postgres/kafka/otlp, prod patches added only 5432/9092/9098. But MSK IAM auth gets its credentials via IRSA's **STS web-identity exchange over HTTPS** before it can even say hello to a broker | On a CNI that enforces NetworkPolicy, both Kafka services are dead on arrival in prod: credential fetch blocked → SASL auth never starts → consumers never join. order-service survived **by accident** — its 0.0.0.0/0:443 rule was written for Cognito JWKS and incidentally covers STS | 443 egress for both, with the reason in the comment (tighter future option: an STS VPC interface endpoint + VPC-CIDR rule). Verified by kubeconform + kyverno in CI; a live-cluster run remains the labs' part, stated honestly |

Walk 73 lesson: **an egress allowlist encodes your mental model of who the pod talks to — and auth layers add invisible dependencies (STS, JWKS, token endpoints) that aren't in the app's own config.** When a pod gains a new auth mechanism, ask what the *bootstrap* of that mechanism dials, not just the data plane. And "the other service works" may mean its rule list is right — or that someone else's broad rule is hiding your missing one.

## Walk 74: two alerts with private copies of the official definition (Nov 9, 2026)

Scope: `recording-rules.yml` + `slo-rules.yml` semantic read. The SLI family is sound (valid = 2xx|5xx, bad = 5xx + slow-2xx, 4xx/429 out of both — Walk 14's definition, consistently restated across the 30m/1h/6h windows; the longer windows must inline it because PromQL can't re-window a recorded ratio). But the header's promise — "every alert uses the same definition of error rate" — was false for the two founding alerts:

| Found | Why it mattered | Fix |
|---|---|---|
| `HighErrorRate` computed its own ratio **without** the `uri!~"/actuator.*"` exclusion that the recorded `service:http_error_ratio:rate5m` (and every dashboard panel) applies; `HighP99Latency` likewise inlined a private copy of the recorded p99 | Actuator scrapes (Prometheus, every 15s) + kubelet probes (liveness/readiness, every 5–10s) are a *constant* stream of fast 2xx. On a low-traffic service they dominate the denominator, so "5% of requests failing" silently meant "well over 5% of *business* requests failing" — the page fires late exactly where traffic is thin. And two definitions of one number is the dashboard-vs-alert drift machine | Both alerts now consume the recorded rules. promtool evaluates recording rules inside unit tests, so Walk 70's new cases (incl. the actuator-exclusion case) and the original HighErrorRate pair verify the switch — same thresholds, same labels, one definition |

Walk 74 lesson: **recording rules exist precisely so "error rate" is defined once — an alert that re-derives the number inline will drift from it** (here: the exclusion list). The audit is mechanical: any alert expr that could be `recorded_rule > threshold` but isn't, is a second definition waiting to disagree.

## Walk 75: the ArchUnit guardrails, verified clean (Nov 9, 2026)

Scope: `ArchitectureTest` — the 7 build-failing rules — read adversarially for false confidence.

- The scope claim is honest everywhere it's made: rules analyze `com.shopflow.order` only, and the notes say "in order-service" rather than implying repo-wide enforcement. (inventory's `ProductController` uses its repository directly — a deliberate thin-read-layer choice that would fail rule 1 if the rules were blindly copied over; extending them is a conscious decision, not a TODO.)
- The DTO rule works at the right granularity: `OrderResponse` is a nested record — a separate class in bytecode — so the `@RestController` class itself stays clear of `@Entity` dependencies while the mapper lives beside it.
- `DoNotIncludeTests` keeps test fixtures from polluting the analysis; the three GeneralCodingRules (no field injection, no System.out, no JUL) hold across main code.

Nothing to change. **Verified clean.**

Walk 75 lesson: architecture tests are claims with a scope — the honest form is "these N rules hold in THIS module", and the audit is checking that the prose never quietly widens the scope the test actually enforces.

## Walk 76: teach-back — Walks 71/73/74 as notes (Nov 9, 2026)

Docs-only: EASY-NOTES §10b ("transaction ke andar remote paisa: do orderings, dono haarte hain" — the idempotency-key contract), a bootstrap-dependency note in Part 5 (STS/JWKS dial before the data plane; egress lists must cover the bootstrap), and the one-definition rule in §13 (alert = `recorded_rule > threshold`, never a private inline copy). No code changed.

## Walk 77: the listener that breaks the house rule — and why that's right (Nov 9, 2026)

Scope: `PaymentEventListener` — the saga's closing consumer — against the repo's own doctrine from `OrderService`: *"deliberately NOT one big @Transactional: holding a DB transaction open while waiting on a remote call exhausts the pool."*

| Found | Why it mattered | Fix |
|---|---|---|
| The listener's `@Transactional` spans `inventoryClient.release()` (with Walk-61's retry budget, ~7s worst case) inside `onPaymentFailed`/`onPaymentRefunded` — exactly what the doctrine forbids, with no comment acknowledging it | Unexplained doctrine violations rot two ways: someone "fixes" it (dropping the tx reopens the dedupe race — processed-row and state change MUST commit together or redeliveries double-apply), or someone copies it into the HTTP path where N concurrent requests would pin N connections. The listener case is actually sound: default concurrency 1 bounds it to ONE pinned connection (20% of prod's pool-5) during an inventory outage, and the failure path is designed (rollback → retries → DLT alert → OrdersStuckConfirmed surfaces the order) | Javadoc now states the exception, the reasoning, the bound, and the failure path — the Walk-71 treatment: a deliberate trade-off is only safe once it's written where the next reader will look. Dedupe shape verified consistent with notification-service (effect before processed-row, at-least-once), unknown event types are logged AND marked processed (no poison loop) |

Walk 77 lesson: **a rule's exceptions need the same documentation rigor as the rule — "why this violation is safe HERE" (the bound) and "what makes it unsafe elsewhere" (the multiplier).** An undocumented exception is indistinguishable from a bug, and both possible "fixes" by a well-meaning reader make it worse.

## Walk 78: the entry point's small lies (Nov 9, 2026)

Docs-only: `INDEX.md` — the file every session reads first — audited against the repo it maps. Three drifts: VISUAL-FLOWS counted "13 flows" (it has 17), the tree omitted the root `labs/` directory entirely (the interactive console + Postman collection the docs elsewhere advertise), and the runbooks line undersold what's there. All fixed. Everything else checked out: every file the table names exists, the reading chain's files exist, the per-file "ye mat likho" boundaries match how the docs are actually used.

Walk 78 lesson: the entry-point doc is the one place drift is cheapest to fix and costliest to leave — a wrong count teaches a reader to distrust the whole map. Re-walk it whenever a counted thing (flows, labs, rules) grows.

## Walk 79: the 30-second snapshot was 20 days old (Nov 9, 2026)

Docs-only: `SUMMARY.md` — the "context reset ke baad yahi padho" page — was dated 2026-10-20 and still listed the Walk-20-resolved `RefundFailed` path as pending, while missing the two decisions STATE §4 actually waits on (publishing gates, dependabot PRs). Status block now carries today's truth: 78 walks, 51 tests + postgres-smoke, 17 alert test cases, publish/sign off with the six-gate census pointer. The irony is noted: a snapshot whose whole job is freshness had no mechanism reminding anyone to refresh it — it now rides the same rule as STATE ("counted things changed → re-walk the entry docs", Walk 78).

## Walk 80: the issuer only the laptop could resolve (Nov 9, 2026)

Scope: the base infra pods nobody had walked — kafka (headless service for `kafka-0.kafka` quorum ✓, single-node RF=1s consistent and documented), redis (allkeys-lru, cache-loss-is-cheap Deployment ✓), postgres — and keycloak, where the find was:

| Found | Why it mattered | Fix |
|---|---|---|
| `KC_HOSTNAME: http://keycloak.shopflow.local` pinned every token's `iss` to the **ingress** hostname — which exists only in the laptop's `/etc/hosts`. order-service's `JWT_ISSUER_URI` matched the string, but the pod can't resolve it: cluster DNS has `keycloak.shopflow.svc`, not `.local` ingress names. interview-notes/11 even claimed "in K8s both sides use the same hostname, so no trick is needed" — the compose file NEEDED a trick (`extra_hosts: localhost:host-gateway`) and got one; k8s needed one and didn't | Every pod boots healthy (SupplierJwtDecoder is lazy; readiness checks only the DB) and then **every authenticated request 401s** on the first JWKS fetch — the exact D09 drill shape, shipped as config. Hidden from all 51 tests (they mint local JWTs) and from kubeconform (valid YAML, wrong world) | `KC_HOSTNAME` pinned to the **in-cluster** URL (`http://keycloak:8180`) and the ConfigMap issuer matched: `iss` no longer depends on which host the browser used, and it names something pods CAN resolve. The browser never validates `iss` — it only needs the token endpoint to answer, and `KC_HOSTNAME_STRICT=false` keeps the ingress path working. Notes/11 now explains the real mechanism instead of denying the need for one |

Walk 80 lesson: **an issuer URL is two promises in one string — "this is who signed it" (a comparison) and "fetch my keys here" (a network call) — and they're validated from different network vantage points.** Any time one URL must serve both the browser's world and the cluster's world, pin it to the vantage point that actually dials it, and check the other side merely tolerates it.

## Walk 81: Walk 80's census (Nov 9, 2026)

Docs-only, by the Step-7 rule: grepped `keycloak.shopflow.local` repo-wide after Walk 80's issuer change. Three stale copies in interview-notes (05's ConfigMap quote, 11's issuer table and its 401-debug answer) now state the in-cluster issuer; the ingress host rule and the `/etc/hosts` line stay — those are the browser's path and still correct. The census habit paid for itself within one walk of teaching it.

## Walk 82: the copied contracts, verified in lockstep (Nov 9, 2026)

Scope: the event records that exist as per-service copies (the repo's stated alternative to a Schema Registry): producer `OrderEvent` vs notification's and payment's copies, producer `PaymentEvent` vs order-service's copy.

Field-for-field identical (names, types, order), the one deliberate divergence is sound — the producer types `status` as the `OrderStatus` enum while consumers take `String` (serialises identically, and a new status value can't break a consumer's deserialisation) — and all three consumer copies carry `@JsonIgnoreProperties(ignoreUnknown = true)`, so the producer can add fields freely. **Verified clean.** (The structural risk stays what STATE §4 says it is: nothing but this walk's grep enforces the lockstep — that's the Schema Registry item's whole case.)

Walk 82 lesson: copied contracts are a census (Step 7) that recurs on every producer change — if you choose copies over a registry, the diff of the copies IS your compatibility check, so actually run it.

## Walk 83: the check that called a dead server "no error" (Nov 9, 2026)

Scope: `loadtest/flash-sale.js` end-to-end (Walk 47 had only fixed the expectedStatuses callback).

| Found | Why it mattered | Fix |
|---|---|---|
| `check(res, { 'no server error': r.status < 500 })` — in k6 a network-level failure (connection refused, timeout before response) is **status 0**, and 0 < 500 | The one check named "no server error" would pass while the server was literally unreachable. The `http_req_failed` threshold still catches it (status 0 isn't an expected status), so the run fails — but with the check lying GREEN next to a red threshold, pointing the reader away from the cause | `r.status >= 200 && r.status < 500` |
| Two implicit assumptions undocumented: `orders_confirmed==3` needs a FRESH seeded stack (a re-run finds 0 units and fails "correctly" but confusingly), and `handleSummary` silently **replaces** k6's default end-of-run report | Both are the "works once, confuses the second person" class | Header note for the fresh-stack requirement; comment stating the summary replacement is a deliberate trade-off (thresholds still gate the exit code) |

Walk 83 lesson: in k6, status 0 exists and every `< N` comparison must decide what it means; and a load test's hidden fixture assumptions (seed state) belong in its header, because the second run is always by someone else.

## Walk 84: alert → runbook → README, all twelve accounted for (Nov 9, 2026)

Docs-only audit of CLAUDE.md's rule #2 ("new alert → runbook + test + README row, teeno"): all 12 alert names cross-checked against `docs/runbooks/` files and the README table. Complete — every alert has its runbook and row; the two burn-rate alerts share `CheckoutSLOBurnRate.md` deliberately (same symptom, different window) and both annotations point there; the three non-alert runbooks (restore, rollback, post-mortem template) are the documented extras. With Walk 70's tests, the triad now genuinely holds: **12 alerts × (runbook + README row + firing-and-silence tests).** Verified clean.

## Walk 85: the last unwalked classes, verified clean (Nov 9, 2026)

Scope: the three remaining never-walked classes — `Caller`, payment's `JpaConfig`, inventory's `CacheConfig`.

- **Caller**: `name` = JWT `sub` (set explicitly by `JwtRolesConverter`), `mayAccess` = support-or-owner. The interesting cross-check: support can *read* anything but **cannot cancel** — not because Caller says so, but because the `DELETE` matcher requires `customer`. Two layers, consistent with the realm's "support: read-only" description.
- **JpaConfig**: `@EntityScan`/`@EnableJpaRepositories` spanning the shared outbox module, kept off the application class so `@WebMvcTest` slices stay light — deliberate and explained.
- **CacheConfig**: JSON values (the serialization trap Walk-era fix, pinned by `CacheSerializationTest`), and `LoggingCacheErrorHandler` = fail-open. One interaction worth verifying after Walk 54: **`sync=true` with Redis down** — Spring's `AbstractCacheInvoker.doGet(cache, key, loader)` catches the provider error via `handleCacheGetError` and invokes the loader directly, so the fail-open promise survives the sync path. It does.

Nothing to change. **Verified clean** — and with this, every main-source class in the four services + shared module has been walked at least once.

Walk 85 lesson: when two features are added at different times (fail-open error handler; `sync=true` years-of-walks later), their INTERACTION is a new, unreviewed surface — "both verified separately" is not "verified together".

## Walk 86: teach product — drill D16, phantom stock (Nov 9, 2026)

Docs-only: Walk 55's bug rebuilt as a symptom-first debugging drill (`docs/debugging/D16-phantom-stock.md` + README row): no alert, a weekly reconciliation finds stock higher than the warehouse, and the solver must (1) find the two-transaction interleaving a green sequential test can't see, (2) name the replay-vs-race distinction, (3) defend the conditional-UPDATE fix against @Version and FOR UPDATE *in this codebase's terms*, (4) write the barrier test, (5) design the drift detector that would have caught it in one tick. The walkthrough carries the full answers.

## Walk 87: the block's vocabulary, filed (Nov 9, 2026)

Docs-only: five terms this block coined or sharpened — replay- vs race-idempotent, phantom stock, replayable window, the census rule, bootstrap dependency — added to GLOSSARY with one-line Hinglish definitions and walk pointers, so the Ctrl+F path finds them.

**CI pin:** `edf5fd8` GREEN (CI + shopflow) — the cumulative run verifying every code change of Walks 73–83: prod-overlay JWT_AUDIENCE + STS egress (kubeconform/kyverno), the recorded-definition alert switch against all 17 promtool cases, the in-cluster issuer pin, and the k6 fixes (`k6 inspect`).

## Walk 88: the aws profile, walked end to end (Nov 9, 2026)

Scope: the MSK IAM chain, link by link — the exact shape of Walk 73's STS finding, one layer up: does every service that gets `SPRING_PROFILES_ACTIVE=aws` actually have the profile file AND the jar?

Verified across all three Kafka services (order, notification, payment): `application-aws.yml` exists and is byte-identical (SASL_SSL + AWS_MSK_IAM + login module + callback handler), `aws-msk-iam-auth` is a runtime-scope dependency in each pom (one version, from the parent property), the prod overlay sets the profile on exactly those three, every one reads `KAFKA_BOOTSTRAP_SERVERS` from the ConfigMap whose prod value is the `:9098` IAM listener, and (since Walk 73) all three have the STS 443 egress the credential fetch needs. inventory-service correctly has none of it. One stale line found: interview-notes/10 claimed the profile file lives "in order- and notification-service" — payment's copy predates the claim's correction. Fixed. **Otherwise verified clean.**

Walk 88 lesson: a profile-activated feature is a chain with four links — the env var, the profile file, the classpath dependency, and the network path — and each lives in a different file owned by a different mental model. Walk the chain per service; "the other services have it" is exactly how the fourth copy gets skipped.

## Walk 89: SKIP LOCKED dedupes pods — it does not order them (Nov 9, 2026)

Scope: `OutboxRelay` + `OutboxRepository`, re-walked with the lenses the earlier sweep didn't have (Walk 53's topology lens, Walk 55's race tiers).

| Found | Why it mattered | Fix |
|---|---|---|
| The relay's "stop at the first failure, keep ordering" comment — and two notes claiming the relay "preserves order" — are true **per pod** only. With HPA min-2, two relays run concurrently *by design* (SKIP LOCKED exists for exactly that), and during a backlog drain adjacent rows of the SAME order land in different pods' pages: pod B can publish the order's LATER event while pod A still holds the earlier one — inverted arrival on the very partition whose key (`orderRef`) exists to guarantee order | Per-aggregate ordering is the outbox design's headline promise. The known inversions are absorbed — consumers are status-guarded state machines, and the nastiest case (OrderCancelled/OrderConfirmed inverted → charge lands on a cancelled order) is precisely the Walk-43 compensation path (auto-refund); a lost-refund shape surfaces via OrdersStuckConfirmed. But "absorbed by three other mechanisms" is a fact to document, not to discover during an incident | Honest comments in relay + repository (what SKIP LOCKED gives and what it doesn't, what absorbs the inversions), overclaim fixed in interview-notes/12, and a STATE §4 item with the two real options if strict per-order delivery is ever required (single-writer via leader election, or hash-partitioned claims `mod(hashtext(aggregate_id), pods)`). No behavior change: both remedies cost ops complexity the current absorbers make unnecessary |

Walk 89 lesson: **"ordered" is a per-WHAT question — per pod, per partition, per aggregate are three different guarantees, and a mechanism that gives one often silently costs another** (SKIP LOCKED buys multi-pod dedupe by giving up cross-pod order). Re-walk old "verified clean" files whenever a new lens enters the toolkit; clean-under-old-lenses is a timestamp, not a property.

## Walk 90: the cache that would die silently on day one in prod (Nov 9, 2026)

Scope: the never-walked Terraform files — `vpc.tf` (subnet math non-overlapping, NAT per AZ, ELB discovery tags ✓), `eks.tf` (IRSA, addons incl. metrics-server for the HPAs ✓ — note: `karpenter.sh/discovery` tags are prep for a Karpenter that isn't installed anywhere; harmless but a named-thing-that-doesn't-exist), `dns.tf` (cleanly optional behind `hosted_zone_id`), `elasticache.tf` — where the chain broke:

| Found | Why it mattered | Fix |
|---|---|---|
| `aws_elasticache_replication_group` sets `transit_encryption_enabled = true` — but inventory-service's Redis client had **no TLS config at all** (`spring.data.redis.host/port` only). A plaintext Lettuce handshake against a TLS-only endpoint fails on every operation | The brutal part is WHO hides it: our own fail-open `LoggingCacheErrorHandler` (a correct design!) turns every failure into a logged miss — so prod ships with a **permanently dead cache that looks green**: readiness is db-only, `REDIS_HEALTH` defaults false, no alert watches cache hit-rate. Every product read goes to Postgres; the flash-sale stampede the cache (and Walk 54's `sync=true`) exists to absorb lands straight on the DB | `spring.data.redis.ssl.enabled: ${REDIS_SSL:false}` (compose/in-cluster redis stay plaintext) + `REDIS_SSL: "true"` in the prod overlay's inventory patch, both with the why. Detection gap noted here honestly: a cache hit-ratio gauge/alert is the structural fix for "fail-open hides misconfig" and goes on the roadmap with the drift detector from D16 |

Walk 90 lesson: **fail-open designs convert hard failures into silent degradation — which means every fail-open path NEEDS a metric watching the thing it fails open around.** And encryption flags are a two-ended contract (Walk 80's issuer lesson, TLS edition): the side that enables TLS and the side that speaks it live in different repos' files, so grep both ends whenever either changes.

## Walk 91: a watchdog for the fail-open cache (Nov 9, 2026)

Walk 90's lesson, implemented the same day — "every fail-open path needs a metric watching the thing it fails open around":

| Piece | What |
|---|---|
| Metric | `spring.cache.redis.enable-statistics: true` → `cache_gets_total{cache="products", result="hit"\|"miss"}` (Boot's Redis cache binder; the `simple` dev cache emits nothing, which is fine — the failure mode being watched is prod's) |
| Alert | `ProductCacheSilentlyDead` (ticket): hits == 0 **while** total gets > 0.1/s, for 15m — the outcome signal that catches ANY silent-death cause (wrong host, missing TLS, auth, policy) without knowing which. The traffic guard keeps no-traffic/no-data silent |
| Tests | #14 (steady misses, zero hits → fires) + #14b (healthy mix → silent) — 19 promtool cases now |
| Runbook | `ProductCacheSilentlyDead.md`: logs → connectivity/TLS (`REDIS_SSL`, Walk 90) → config → blast radius; and why it's a ticket, not a page (fail-open means users are fine until the next spike) |

CLAUDE.md's trio (alert + test + runbook + README row) shipped together.

Walk 91 lesson: watch **outcomes, not causes** — one hit-rate alert covers every way the cache can silently die, including the ways nobody has imagined yet. Cause-shaped alerts (one per misconfig) are whack-a-mole; outcome-shaped alerts are the net under the whole act.

## Walk 92: the backup nobody would miss until the restore (Nov 9, 2026)

Scope: `postgres/backup-cronjob.yaml` re-walked with Walk 91's outcome-watchdog lens. The job itself is genuinely well-built — `set -euo pipefail`, dump-to-`.part`-then-rename (a truncated dump can never masquerade as a good backup), retention delete only after success, `concurrencyPolicy: Forbid`, deadline, non-root. The gap was around it:

| Found | Why it mattered | Fix |
|---|---|---|
| Nothing watches whether the backup actually HAPPENS. `failedJobsHistoryLimit: 3` keeps evidence for a human who never looks; a suspended CronJob, a schedule typo, a full 2Gi PVC or a rotated password all fail (or skip) silently, forever | Backups are the textbook silent-failure: the first person to notice a year of missed backups is the person doing the restore. RPO is a promise about the WORST day, kept or broken on all the ordinary days nobody checked | `PostgresBackupMissed` (ticket): `time() - kube_cronjob_status_last_successful_time > 26h` — **freshness, not failure-count**, so "failed" and "never ran" are the same alert. Promtool tests #15/#15b (21 cases now), runbook (triage order: suspended → history → PVC/secret/deadline/policy; manual `create job --from=cronjob` verify; restore-drill reminder), README row. Honest dependency stated in the rule: metric needs kube-state-metrics — the lab cluster's part, same class as Walk 51's labelmap note |

Walk 92 lesson: **a scheduled job's success history answers "did it work when it ran" — freshness answers "is it running at all", and only freshness survives the failure modes where the job never starts.** Alert on `last_successful_time`, not on failure counts.

## Walk 93: the exporters that were on but walled off (Nov 9, 2026)

Scope: `msk.tf`, re-walked with Walk 90's two-ended-contract lens. The cluster config is sound (RF 3 / minISR 2 / auto-create off, IAM-only auth, TLS in transit + at rest, broker logs to CloudWatch). The gap mirrors Walk 90 exactly:

| Found | Why it mattered | Fix |
|---|---|---|
| `open_monitoring.prometheus` enables the JMX exporter (:11001) and node exporter (:11002) "scraped by the in-cluster Prometheus" — but the MSK security group's only ingress was 9092–9098 (the Kafka listeners). Ports 11001/11002 were **enabled on the broker and blocked at the SG** | Same shape as Walk 90's Redis TLS: a feature switched on at one end, its port closed at the other, and a comment ("scraped by Prometheus") asserting a path the security group silently denies. Broker dashboards/alerts would be permanently empty with no error — just no data | Second ingress rule for 11001–11002 from the cluster SG only, with the why. Same `security_groups = local.cluster_sg_ids` scoping as the Kafka rule — brokers stay unreachable from anywhere but the nodes |

Walk 93 lesson: `open_monitoring` / "enable exporter" flags turn the metric *source* on; the *path* to it is a separate firewall/SG/NetworkPolicy decision in another resource. Every "enabled_in_broker = true" (or its Redis/RDS equivalent) owes a port-reachability check at the other end.

## Walk 94: the fix was right, the test couldn't see it fire (Nov 10, 2026)

Scope: a RED CI — Walk 92's push (`58baa41`) failed the `shopflow` validate job on `promtool test rules`. Not a flaky infra download (those were Walks 44/48); a real test defect I shipped in Walk 91.

| Found | Why it mattered | Fix |
|---|---|---|
| `ProductCacheSilentlyDead` carried `for: 15m`, and its promtool test evaluated at `eval_time: 16m` expecting the alert to fire — but got `[]`. `rate(...[15m])` needs ≥2 points before it returns anything, so the alert condition can only start being true at minute ~2; from there to minute 16 is 14 minutes of pending time, **less than the 15m `for`** — so at eval time the alert was still PENDING, correctly un-fired. The alert logic was fine; the `for` was both too long and untested-against | A green local reason ("the expr is obviously right") shipped a red CI, because I didn't run promtool before pushing (no Docker this session) and the 16m window hid a 15m `for`. The 15m `for` on top of a 15m rate window was also just slow — double-counting the smoothing | `for: 15m → 5m` (the `[15m]` rate window already supplies the sustained-behaviour smoothing; 5m only guards a single-eval blip), with a comment explaining both the semantics and the test trap. Now fires by minute ~7, well inside the 16m test. Walk 92's PostgresBackupMissed tests had passed — only this one was red |

Walk 94 lesson: **`for:` and a `rate([window])` both impose latency, and they stack** — a `for` as long as the rate window roughly doubles detection time for no extra signal. And the honest one: I pushed a monitoring change I couldn't run locally and trusted the expression's obviousness; the test existed precisely to catch what obviousness misses, and it did — the process worked, my pre-push check didn't. Where promtool can't run locally, the alert-under-test deserves extra scrutiny of `for` vs the test's eval window.
