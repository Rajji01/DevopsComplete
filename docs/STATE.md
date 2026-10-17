# STATE: single source of truth for this repo

**Read this first** (after [`START-HERE.md`](START-HERE.md) if you are new). It says what exists, which approach is "basic" and which is "advanced" for each topic (both are kept on purpose), what has been verified and how, and what is still open. Every walk updates it. If a document disagrees with this file, this file wins and the document gets fixed.

Last updated: 2026-10-17 (Walk 6). Branch: `claude/modest-bardeen-r4czhv`.

## 1. What is in the repo

| Area | Path | Status |
|---|---|---|
| Learning project v0 | `Devops/`, `microservice01/`, `notes.txt` | kept as the "before" picture; manifests lint-clean and kubeconform-valid |
| ShopFlow services | `shopflow/{order,inventory,notification,payment}-service`, `shopflow-outbox` | 46 tests green: inventory 11, order 29, notification 2, payment 4 |
| Local stack | `shopflow/docker-compose.yml` | Postgres, Kafka, Redis, Keycloak, 4 services, Prometheus, **Alertmanager**, Tempo, Loki, Alloy, Grafana (dashboards provisioned) |
| Kubernetes | `shopflow/k8s/` (Kustomize), `shopflow/helm/` (Helm), `shopflow/k8s/rollouts/` (Argo Rollouts), `shopflow/k8s/policies/` (Kyverno, Gatekeeper) | all rendered + schema-validated |
| Monitoring | `shopflow/monitoring/` | alert rules (7 + 2 SLO), recording rules, **unit-tested** alerts, Alertmanager routing, Grafana dashboard |
| Runbooks | `docs/runbooks/` | one per alert + restore, rollback, post-mortem template |
| AWS | `infra/terraform/aws/` (+ `environments/{dev,prod}.tfvars`) | fmt + tflint core clean; validate/plan only in CI / your account |
| VM alternative | `infra/ansible/` | syntax-check + ansible-lint clean |
| CI/CD | `.github/workflows/shopflow.yml`, `shopflow/Jenkinsfile`, `shopflow/argocd/` | lint → test → validate → build → scan → SBOM → push → sign → pin SHA |
| Dev hygiene | `Makefile`, `.pre-commit-config.yaml`, `.yamllint.yaml`, `.hadolint.yaml`, `.gitleaks.toml` | `make lint`, `make validate` |
| Learning docs | `docs/EASY-NOTES.md`, `docs/LABS.md` (break/fix), `docs/PRACTICE-LABS.md` (build it yourself, with solutions), `docs/ROADMAP.md`, `docs/SELF-WALK.md`, `docs/interview-notes/` | synced with this file at every walk |

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
| Java code | `mvn verify` (46 tests) |
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
| k6 run, cosign signing, SBOM, Kyverno signature verification | CI (`k6 inspect`, signing/SBOM on `main`); verification needs a cluster with Kyverno |
| Ansible against a real VM | user |

## 4. Open items (next walks)
- Refund flow: cancelling a `PAID` order (`RefundRequested` → payment-service → `REFUNDED`).
- `CONFIRMED` orders whose payment never arrives: timeout + reconciliation.
- Shared (Redis/gateway) rate limit instead of per-pod.
- Schema Registry (Avro/Protobuf) for event contracts; Testcontainers for PostgreSQL-only SQL.
- Kyverno signature policy is written but needs a real signed image to test end to end.
- Chaos experiments (kill a broker / RDS failover) under the k6 load test.

## 5. How to keep this file true
1. Every walk: update sections 1–4 **in the same commit** as the change.
2. `make lint && make validate && make test` must pass before the commit (CI runs the same).
3. New approach for an existing topic → add a row to the matrix, keep the old approach, write the comparison where the matrix says.
4. New alert → runbook + unit test + row in `docs/runbooks/README.md`.
