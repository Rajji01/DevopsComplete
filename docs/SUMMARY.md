# SUMMARY — 30-second project snapshot (context reset ke baad yahi padho)

Agar kisi ka (tera ya Claude ka) context reset ho gaya hai: entry point **[`INDEX.md`](INDEX.md)** (poora naksha), aur quick picture ke liye **ye page + [`STATE.md`](STATE.md) §1 + [`SESSION-LOG.md`](SESSION-LOG.md) ka top entry**. Deep map chahiye to [`START-HERE.md`](START-HERE.md).

## Project ek line mein
DevOps + Java backend **mastery + interview prep** repo. Dil = `shopflow/` — ek e-commerce checkout ka production-style microservices system, aur uske aas-paas ki saari DevOps machinery (deploy, monitor, secure, scale).

## Kya-kya hai (bade tukde)
| Tukda | Kahan | Ek line |
|---|---|---|
| 4 services + outbox lib | `shopflow/{order,inventory,payment,notification}-service`, `shopflow-outbox` | order lo → stock reserve → pay → confirm/cancel; Kafka saga + transactional outbox |
| Local stack | `shopflow/docker-compose.yml` | Postgres, Kafka, Redis, Keycloak, 4 services, Prometheus, Alertmanager, Tempo, Loki, Grafana — `make up` |
| Kubernetes | `shopflow/k8s/` (Kustomize), `helm/`, `k8s/rollouts/`, `k8s/policies/` | deploy ke basic→advanced tarike side by side |
| AWS prod | `infra/terraform/aws/` | VPC, EKS, RDS, MSK, ElastiCache, Cognito (padhne ke liye, create optional) |
| VM wala tarika | `infra/ansible/` | Kubernetes ke bina, ek VM pe wahi stack |
| Monitoring | `shopflow/monitoring/` | alert rules + SLO burn-rate + unit-tested alerts + Grafana dashboard |
| Runbooks | `docs/runbooks/` | har alert pe kya karna, + restore/rollback/post-mortem |
| CI/CD | `.github/workflows/shopflow.yml`, `Jenkinsfile`, `argocd/` | lint→test→validate→build→scan→SBOM→sign→push |
| Seekhne ke docs | `docs/EASY-NOTES.md`, `LABS.md`, `PRACTICE-LABS.md`, `interview-notes/` | concept → labs → interview answers |
| Purana setup | `Devops/`, `microservice01/`, `notes.txt` | "before" picture, jaan-bujh ke rakha |

## Abhi status (2026-11-09)
- **master = full project**, origin se in-sync; **78 self-walks** ho chuki (har ek SELF-WALK.md mein — bug stories + clean sweeps).
- Java: **51 tests green in CI** (JDK 21) + naya `postgres-smoke` CI job: chaaron services asli Postgres 16 pe Flyway+validate boot karti hain (Walk 57). Alerts: **17 promtool test cases** — har alert ka firing + silence test (Walk 70).
- Live cluster / AWS apply **yahan verify nahi** hote — CI ya tumhare labs mein (STATE.md §3 saaf batati hai kya-kya). Image **publish/sign off hai**: saare gates nonexistent `main` pe (6 copies, 5 systems — STATE §4 ka decision item).

## Order ki life (ek line yaad rakhne wali)
`PENDING → (stock ok) CONFIRMED → (payment captured) PAID → (cancel) REFUND_PENDING → REFUNDED`
Jahan paisa **nahi** chala (PENDING/FAILED) wahan auto-cancel safe; jahan chal **sakta** hai (CONFIRMED/REFUND_PENDING) wahan sirf alert (full diagram: STATE.md §1).

## Abhi kya pending (poori list STATE.md §4)
**publishing decision** (6 main-gates ek saath flip karo ya design-only rakho) · ~9 dependabot PRs · shared rate-limit · Schema Registry · Kyverno signature e2e test · chaos experiments · orphaned-CONFIRMED saga timeout. (`RefundFailed` path Walk 20 mein resolve ho chuka.)

## Agla kadam uthane se pehle
1. [`SESSION-LOG.md`](SESSION-LOG.md) ka top entry padho — pichhli session kya chhod gayi.
2. [`CLAUDE.md`](../CLAUDE.md) — kaam karne ke rules (source of truth, branch, verify).
3. Change kiya → `make lint && make validate && make test` → STATE.md + SESSION-LOG update → commit.
