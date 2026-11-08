# STATE: single source of truth for this repo

**Read this first** (after [`START-HERE.md`](START-HERE.md) if you are new). It says what exists, which approach is "basic" and which is "advanced" for each topic (both are kept on purpose), what has been verified and how, and what is still open. Every walk updates it. If a document disagrees with this file, this file wins and the document gets fixed.

Last updated: 2026-11-08 (Walk 51: canary AnalysisTemplate ab specific `canary-hash` filter karta — `!=""` stable+canary blend naapta tha, chhote weight pe kharab canary pass ho jaata. Walk 50: publish/sign/pin sab nonexistent `main` pe gated — kabhi chale nahi; STATE/§4/policy ab sach bolte hain, enable karna Abhishek ka decision. Walk 49: .dockerignore += helm/ loadtest/ — GHA buildx cache ab unrelated edits pe bust nahi hota. Walk 48: ansible repo_version main→master — main exist nahi karta, deploy checkout pe hi marta tha. Walk 47: k6 flash-sale — expectedStatuses(409,429) warna rate limiter ke 429s hi threshold fail karte. Walk 46: zero-trust/backup/listener sweep all-clean. Walk 45: idempotency-key race — placeOrder catches the unique-constraint loss and returns the winner (inventory pattern), counter orders.idempotency.race. Walk 43: cancel/charge race fixed — PaymentCaptured on a CANCELLED order now compensates via REFUND_PENDING → refund saga, counter orders.cancel.charge.race. Walk 44: all 6 "curl | tar" CI tool-downloads → download-to-file with --retry (a truncated byte failed Walk 42's validate job). Walk 42: PriceList unknown-sku fallback (999.00) removed — decline instead of made-up charge + refund no-op logging. Walk 41: dashboard panel 12 — refund-declined + dead-lettered stats on shopflow-red.json. Walk 40: `kafka.dead.lettered` counter in all 3 `KafkaErrorConfig`s + `KafkaMessageDeadLettered` alert + promtool tests + runbook — a DLT record is no longer silent. Walk 39: jitter on inventory retry — `enable-randomized-wait`+`randomized-wait-factor: 0.5` → `ofExponentialRandomBackoff`, de-syncs retry waves across pods / thundering herd). Prior: Walk 38 (`Persistable`+`isNew()==true` on the 3 assigned-UUID insert-only entities — closes Walk-21 follow-up), Walk 36 (PaymentRefundFailed alert+runbook+test), Walks 22–35 (web: drills/comprehension/audits), 21 (outbox persist), 20 (refund decline). Branch: `master`.

## 1. What is in the repo

| Area | Path | Status |
|---|---|---|
| Learning project v0 | `Devops/`, `microservice01/`, `notes.txt` | kept as the "before" picture; manifests lint-clean and kubeconform-valid |
| ShopFlow services | `shopflow/{order,inventory,notification,payment}-service`, `shopflow-outbox` | 51 tests: inventory 11, order 32 (+1 SLO-bucket guard, Walk 8), notification 2, payment 6. 50 verified earlier via `mvn verify`; the Walk 8 order-service test is verified in CI (JDK 21) — this machine has no JDK 21 |
| Local stack | `shopflow/docker-compose.yml` | Postgres, Kafka, Redis, Keycloak, 4 services, Prometheus, **Alertmanager**, Tempo, Loki, Alloy, Grafana (dashboards provisioned) |
| Kubernetes | `shopflow/k8s/` (Kustomize), `shopflow/helm/` (Helm), `shopflow/k8s/rollouts/` (Argo Rollouts), `shopflow/k8s/policies/` (Kyverno, Gatekeeper) | all rendered + schema-validated |
| Monitoring | `shopflow/monitoring/` | alert rules (8 + 2 SLO), recording rules, **unit-tested** alerts, Alertmanager routing, Grafana dashboard |
| Runbooks | `docs/runbooks/` | one per alert + restore, rollback, post-mortem template |
| AWS | `infra/terraform/aws/` (+ `environments/{dev,prod}.tfvars`) | fmt + tflint core clean; validate/plan only in CI / your account |
| VM alternative | `infra/ansible/` | syntax-check + ansible-lint clean |
| CI/CD | `.github/workflows/shopflow.yml`, `shopflow/Jenkinsfile`, `shopflow/argocd/` | lint → test → validate → build → scan → SBOM → push → sign → pin SHA |
| Dev hygiene | `Makefile`, `.pre-commit-config.yaml`, `.yamllint.yaml`, `.hadolint.yaml`, `.gitleaks.toml` | `make lint`, `make validate` |
| Learning docs | `docs/EASY-NOTES.md`, `docs/LABS.md` (break/fix), `docs/PRACTICE-LABS.md` (build it yourself, with solutions), `docs/ROADMAP.md`, `docs/SELF-WALK.md`, `docs/interview-notes/` | synced with this file at every walk |
| Debugging drills | `docs/debugging/` (D01–D15 + README) | Real-time debugging practice: given only a symptom (alert/graph/log), diagnose live — signals → differential → root cause → blast-radius-safe fix → the transferable pattern. Grounded in shopflow failure modes (consumer lag, outbox backlog, latency saturation, OOMKill, silent SLO alert, oversell, duplicate-effect idempotency gap, one-partition poison-pill stall, blanket-401 after IdP change, fail-open cache → DB stampede, readiness cascade, silently-growing DLT, rollout paused-vs-stuck, deploy-drops-requests preStop race, HPA flapping). Carries a debugging method (blast radius → what changed → path bisect → RED/USE → differential → confirm → safe fix). Reviewed against source, not executed here |
| Comprehension rounds | `docs/comprehension/` (01–20, each `comprehension.md` + `notes.md`) + `README.md` + `ONLINE-LABS.md` | Google-style comprehension prep: cold passage → 5-tier Qs + answers → separate concept notes (kya/kyu/kaise/diagram/alternate) → local (minikube/docker) + online (Codespaces/Cloud Shell/Killercoda/Render) hands-on. 20 topics: outbox, saga, idempotency, limbo/reconciler, overselling, SLO burn-rate, k8s readiness, supply-chain signing, resilience4j breaker/retry, OAuth2/JWT/IDOR, Kafka DLT/lag, caching fail-open, tracing, Kustomize-vs-Helm, canary/blue-green, virtual-threads, ArchUnit-guardrails, HPA/PDB-autoscaling, backups-RPO/RTO, EKS-IRSA-least-privilege. Grounded in real shopflow code with file paths; passages/commands reviewed against source, not yet executed on this machine (no Docker/JDK here) |

### Order state machine (after Walk 7)

```
PENDING ──reserve ok──▶ CONFIRMED ──PaymentCaptured──▶ PAID ──cancel──▶ REFUND_PENDING ──PaymentRefunded──▶ REFUNDED
   │                        │                                                  (release stock)
   │ reserve 409/404        │ PaymentFailed (release stock)
   ▼                        ▼
REJECTED                 CANCELLED ◀── cancel (CONFIRMED: release stock)
   ▲
inventory unreachable ──▶ FAILED ──reconciler (grace)──▶ CANCELLED
PENDING too long ────────────────reconciler (grace)────▶ CANCELLED
CONFIRMED/REFUND_PENDING too long ──▶ (NOT auto-cancelled) ──▶ gauge orders_stuck_confirmed + OrdersStuckConfirmed alert
```
Rule: a state where **no money has moved** (PENDING, FAILED) is safe to auto-cancel; a state where it **might have** (CONFIRMED, REFUND_PENDING) is only alerted on.
Refund decline (Walk 20): if payment-service's gateway declines the refund, it emits `PaymentRefundFailed`, the payment stays `CAPTURED` and the order stays `REFUND_PENDING` (never faked as REFUNDED) — the same stuck-alert pages a human, and a redelivered `OrderRefundPending` retries.

## 2. Approach matrix: basic ↔ advanced (both kept, on purpose)

| Topic | Basic (start here) | Advanced (when the basic one hurts) | Where the comparison lives |
|---|---|---|---|
| Deploying a service | `Deployment` + RollingUpdate (`k8s/base`) | Argo Rollouts canary with Prometheus analysis / blue-green (`k8s/rollouts`) | `k8s/rollouts/README.md` |
| Packaging manifests | Kustomize base + overlays | Helm chart with values per service/env | `helm/README.md` |
| Running the stack | Docker Compose on one VM via Ansible (`infra/ansible`) | EKS via Terraform + Argo CD | `infra/ansible/README.md` |
| Where prod state lives | in-cluster Postgres/Kafka/Redis/Keycloak (dev overlay) | RDS / MSK / ElastiCache / Cognito (prod overlay) | `k8s/overlays/prod/kustomization.yaml` |
| Secrets | `secretGenerator` literals (dev only) | External Secrets Operator ← AWS Secrets Manager (IRSA) | `k8s/overlays/prod/external-secret.yaml` |
| Admission policy | none / review | Kyverno (YAML) **and** Gatekeeper (Rego) for the same rule | `k8s/policies/kyverno/README.md` |
| Image | alpine JRE, shell available (`Dockerfile`) | distroless, no shell (`Dockerfile.distroless`) | header of `Dockerfile.distroless` |
| Alerting | symptom alerts with `for:` (`alert-rules.yml`) | SLO + multi-window burn-rate alerts (`slo-rules.yml`) | `docs/runbooks/CheckoutSLOBurnRate.md` |
| Alert delivery | Prometheus /alerts page | Alertmanager routing: page → PagerDuty+Slack, ticket → Slack, inhibition | `monitoring/alertmanager.yml` |
| Backups | nightly `pg_dump` CronJob to a PVC (dev) | RDS snapshots + PITR (prod) | `docs/runbooks/RestorePostgres.md` |
| CI | GitHub Actions | the same pipeline as a Jenkinsfile; GitOps deploy via Argo CD | `docs/interview-notes/06-cicd-gitops.md` |
| Supply chain | Trivy scan | + SBOM (CycloneDX) + cosign keyless signing + Kyverno `verifyImages` | `k8s/policies/kyverno/verify-image-signature.yaml` |
| Infra linting | `terraform fmt` | tflint core (local) + AWS ruleset (CI), pre-commit hooks | `.pre-commit-config.yaml` |
| Testing | unit + slice tests | full-context with embedded Kafka/WireMock, ArchUnit, k6 load test with thresholds | `shopflow/loadtest/flash-sale.js` |

## 3. Verification status (honest)

| Verified here, every walk | How |
|---|---|
| Java code | `mvn verify` (50 tests) |
| All YAML / Dockerfiles / secrets / Terraform / Ansible | yamllint, hadolint, gitleaks, tflint (core), ansible-lint + syntax-check |
| Kubernetes manifests (dev, prod, rollouts, policies) | kustomize build → kubeconform (`-strict`, CRD schemas from the datreeio catalog) |
| Policies | `kyverno apply` on the rendered prod overlay (pass) and on `k8s/policies/tests/bad-pod.yaml` (fail) |
| Prometheus rules | `promtool check rules` + **`promtool test rules`** (6 scenarios) |
| Alertmanager | `amtool check-config` |

| Not verified here (no Docker daemon / blocked downloads) | Where it is verified |
|---|---|
| image builds, compose stack, minikube, Argo Rollouts/Kyverno in a live cluster | CI builds images; the rest is the user's labs |
| Helm `lint`/`template` | CI |
| `terraform validate` / `plan` / `apply`, tflint AWS ruleset | CI (validate, tflint); plan/apply need an AWS account |
| k6 run, cosign signing, SBOM, Kyverno signature verification | CI runs `k6 inspect` + SBOM on **every** build; but GHCR push + cosign sign + prod-overlay pin are gated on `refs/heads/main`, **and `main` does not exist** (Walk 50) — so no image has ever been published or signed, and Kyverno signature verification has nothing real to verify yet. Enabling publishing = a deliberate decision (see §4) |
| Ansible against a real VM | user |

## 4. Open items (next walks)
- Shared (Redis/gateway) rate limit instead of per-pod.
- Schema Registry (Avro/Protobuf) for event contracts; Testcontainers for PostgreSQL-only SQL.
- Kyverno signature policy is written but needs a real signed image to test end to end.
- **Publishing is effectively disabled (Walk 50):** GHCR push / cosign sign / prod-overlay pin are all `refs/heads/main`-gated and the repo has **no `main` branch** (only `master`) — so they have never run; no `ghcr.io/rajji01/shopflow-*` image exists, and the prod overlay/Kyverno signature policy reference images+signatures that were never produced. Abhishek's decision: (a) gate on `master` to start publishing (public GHCR packages + signing; also update the Kyverno keyless `subject` from `@refs/heads/main` to `@refs/heads/master`), or (b) keep publishing off and treat prod overlay as design-only. Until decided, docs must not imply images exist.
- Chaos experiments (kill a broker / RDS failover) under the k6 load test.
- Refund gateway failures — **resolved (Walk 20):** `OrderConfirmedListener.refund` now checks the gateway `Result` (it was ignored — a declined refund was faked as success). On decline it emits `PaymentRefundFailed`, leaves the payment `CAPTURED` and the order `REFUND_PENDING` (so the existing `OrdersStuckConfirmed` alert pages a human); a redelivery retries. `payment.refund-limit` (set in the test profile) makes the fake gateway decline a PS5 refund. Verified by `PaymentServiceApplicationTests.declinedRefundPublishesRefundFailedAndKeepsPaymentCaptured`.
- Orchestrated saga timeout for genuinely orphaned CONFIRMED orders (currently alert-only, human decides).
- SLO SLI — **resolved (Walk 14):** it counted slow 4xx/429 as "bad" and had 429s in the denominator. Redefined valid events as 2xx-or-5xx (`status=~"[25].."`), bad = `5xx + slow-2xx`; 4xx/429 excluded from both. Pinned by alert test #7 (slow-429 burst → no page). `promtool` verified in Docker.
- **CI pipeline — FULLY GREEN on `master`** (Walk 8 turned it on there; Walks 11–12 fixed it; run `41297074849`, 2026-11-01). All jobs pass: tests, lint/tflint, k6, validate (kustomize/kyverno/promtool/helm), terraform, 4 image builds + Trivy scan + SBOM. `Pin prod overlay` stays skipped (main-only). Fixes, in the order found — each unmasked the next:
  - `k6 inspect`: double path (`working-directory: shopflow` + `shopflow/loadtest/...`) → `loadtest/flash-sale.js`.
  - `tflint`: `elasticache.tf` used the uneditable `default.redis7` → owned `aws_elasticache_parameter_group`.
  - helm: `tar: helm: Cannot open: File exists` (collided with the `helm/` dir) → install helm into `runner.temp`, call bare `helm`.
  - Flaky tests: live `OutboxRelay` `@Scheduled` raced assertions → isolate `OutboxCleanupTest` (relay parked `outbox.relay.delay=1h`), 30s await on the publish test.
  - `sigstore/cosign-installer@v4` doesn't exist (`uses:` resolve at setup regardless of `if:`) → `@v3`; `fail-fast: false` on the image matrix.
  - Image-ref casing (`ghcr.io/Rajji01/...`) → a step sets `IMAGE` from a lowercased owner (OCI refs must be lowercase).
  - Image CVEs (Trivy, fixable HIGH/CRITICAL): pom property overrides for Boot-managed deps (`jackson-bom` 2.21.7, `netty` 4.1.137.Final, `tomcat` 10.1.59, `postgresql` 42.7.12, `httpcore5` 5.4.3) + `<dependencyManagement>` for the kafka-clients-pinned ones NOT Boot-managed (`at.yawk.lz4:lz4-java` 1.11.4, `snappy-java` 1.1.10.9). `spring-webmvc` (Spring-7-only fix) triaged in `/.trivyignore`.
  - Backup hygiene: nightly `pg_dump` now writes `.part` + atomic `mv`, so a failed dump never looks like a good backup.
  - Basics `ci.yml` (Devops/microservice01, runs on every push) was **also red since it was added** (Walk 13): `docker build -t Devops:ci` → "repository name must be lowercase". Fixed by lowercasing the tag (`${SERVICE,,}`) + `fail-fast: false`. Both workflows now green.
- **OutboxRelay (open, low priority):** `publishPending()` holds its `@Transactional` across the Kafka `send().get(5s)`; bounded (returns on first failure) but move broker I/O out of the DB transaction if the Hikari pool is ever pressured.
- **Spring Boot 4 / Spring 7 upgrade (open):** the proper fix for the triaged `spring-webmvc` CVEs and a chance to drop the pom CVE-patch overrides. A major upgrade — plan and test deliberately.
- **Persistable follow-up — DONE (Walk 38):** `ProcessedEvent` (order + notification) and `Payment` now `implements Persistable<UUID>` with `isNew(){return true;}`, so their assigned-UUID insert-only `save()` is a plain INSERT instead of `merge()`+SELECT (same fix `OutboxEvent` got in Walk 21). Payment's refund mutates the loaded entity via dirty-checking (`markRefunded`), never `save()`, so this stays correct.

## 5. How to keep this file true
1. Every walk: update sections 1–4 **in the same commit** as the change.
2. `make lint && make validate && make test` must pass before the commit (CI runs the same).
3. New approach for an existing topic → add a row to the matrix, keep the old approach, write the comparison where the matrix says.
4. New alert → runbook + unit test + row in `docs/runbooks/README.md`.
