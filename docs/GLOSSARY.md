# GLOSSARY — "kiska kya matlab" (Hinglish, ek line each)

Jab koi folder, file ya shabd samajh na aaye to yahan Ctrl+F. Deep samajh ke liye `EASY-NOTES.md`.

## A. Folders / files — kiska kya kaam
| Cheez | Matlab |
|---|---|
| `CLAUDE.md` | Claude sessions ke rules (kaise kaam karna). |
| `docs/SUMMARY.md` | 30-second snapshot — reset ke baad yahi padho. |
| `docs/STATE.md` | **Single source of truth** — kya hai, kya verified, kya pending. Jhagda ho to yahi jeet-ta hai. |
| `docs/SESSION-LOG.md` | Session-to-session handoff (newest first): kya hua, kya pending. |
| `docs/START-HERE.md` | Poora map + ek-din ka padhne ka plan + "kahan hai X". |
| `docs/EASY-NOTES.md` | Har pattern ka problem→kyu→kaise, simple Hinglish. |
| `docs/LABS.md` | Break/fix labs (cheez todo, phir theek karo). |
| `docs/PRACTICE-LABS.md` | Khud banao tasks + solution. |
| `docs/ROADMAP.md` | 18-week mastery plan. |
| `docs/SELF-WALK.md` | Har bug ki kahani: kyu tha, kaise fix hua (interview gold). |
| `docs/runbooks/` | Alert aaye to kya karna, step by step. |
| `docs/interview-notes/` | Topic-wise interview answers (01…12 + README rapid-fire). |
| `shopflow/` | Asli microservices project (dil). |
| `shopflow/*-service/` | Ek-ek service ka Java (Spring Boot) code. |
| `shopflow/shopflow-outbox/` | Saari services ka shared transactional-outbox code. |
| `shopflow/k8s/` | Kubernetes manifests — `base/` + `overlays/{dev,prod}` (Kustomize). |
| `shopflow/helm/` | Wahi deploy Helm chart se (dusra tarika). |
| `shopflow/k8s/rollouts/` | Argo Rollouts — canary / blue-green deploy. |
| `shopflow/k8s/policies/` | Kyverno + Gatekeeper admission rules. |
| `shopflow/monitoring/` | Prometheus/Alertmanager rules, Grafana dashboard. |
| `shopflow/loadtest/` | k6 flash-sale load test. |
| `infra/terraform/aws/` | AWS prod infra as code. |
| `infra/ansible/` | Kubernetes ke bina, ek VM pe stack (purana tarika). |
| `Devops/`, `microservice01/`, `notes.txt` | Pehla simple learning setup ("before" picture). |
| `Makefile` | Saare commands ka ek darwaza — `make help`. |

## B. Core patterns — matlab
| Shabd | Ek line |
|---|---|
| **Microservice** | Bada app chhote-chhote alag deployable services mein toda. |
| **Saga** | Multi-service transaction bina distributed lock ke; fail ho to compensate (ulta step). |
| **Transactional outbox** | DB change + "event bhejna hai" ek hi DB transaction mein likho; alag relay baad mein Kafka pe bhejta hai → event kabhi lost nahi. |
| **Outbox relay** | Background job jo outbox table se unsent events uthа ke Kafka pe daalta hai (SKIP LOCKED se race-safe). |
| **Idempotency** | Same request/event do baar aaye to effect ek hi baar (duplicate = no-op). |
| **Idempotency-Key** | Client ka header jisse order do baar create na ho. |
| **Reconciler** | Background job jo atke hue orders ko grace ke baad theek/cancel karta hai. |
| **At-least-once** | Event kam-se-kam ek baar milega (shayad do baar) → isliye consumer idempotent chahiye. |
| **DLT (dead-letter topic)** | Jo message baar-baar fail kare use alag topic mein daal do, baaki ruke na. |
| **Circuit breaker** | Downstream service girne pe call karna band, thodi der baad try (Resilience4j). |
| **Retry / timeout** | Transient fail pe dobara; lekin bounded time mein. |

## C. Kubernetes / deploy — matlab
| Shabd | Ek line |
|---|---|
| **Pod** | Kubernetes mein chalne wali sabse chhoti unit (ek ya zyada container). |
| **Deployment** | Pods ki desired count + rolling update manage karta hai. |
| **Service (ClusterIP/NodePort/LB)** | Pods ko ek stable naam/IP deta hai; andar/bahar se pahunchne ke tarike. |
| **Kustomize** | Base manifests + env-wise overlays (dev/prod) bina templating. |
| **Helm** | Manifests ko values se template karke package karne wala tool (chart). |
| **Argo Rollouts** | Deployment ka smart version — canary/blue-green + auto analysis. |
| **Canary / Blue-green** | Naya version thode traffic ko / parallel environment mein, phir switch. |
| **HPA** | Load ke hisaab se pods auto ghatao-badhao. |
| **PDB** | Ek saath kitne pods down ho sakte hain ki limit (availability). |
| **NetworkPolicy** | Kaun-si pod kis-se baat kar sakti hai, firewall rule. |
| **Kyverno / Gatekeeper** | Admission policies — galat manifest (e.g. `latest` tag) cluster mein ghusne mat do. |
| **Probe (liveness/readiness)** | Pod zinda hai? traffic ke liye taiyaar hai? ke health checks. |
| **Distroless** | Chhoti, shell-less image → kam attack surface. |

## D. Observability / SRE — matlab
| Shabd | Ek line |
|---|---|
| **Prometheus** | Metrics scrape + store + query (PromQL). |
| **Grafana** | Metrics ke dashboards/graphs. |
| **Alertmanager** | Alerts ko route/group/silence karke PagerDuty/Slack bhejta hai. |
| **SLO / SLI** | Target (e.g. 99.9% checkout ok) / usko naapne wala metric. |
| **Burn-rate alert** | SLO budget kitni tezi se khatam ho raha — multi-window se smart alert. |
| **Recording rule** | Mehngi query pehle se compute karke store. |
| **Runbook** | Alert aaye to on-call kya kare, step-by-step. |
| **Tempo / Loki** | Distributed traces / logs store. |
| **RED metrics** | Rate, Errors, Duration — service health ke 3 number. |

## E. AWS / infra — matlab
| Shabd | Ek line |
|---|---|
| **Terraform** | Infra ko code se banao (VPC, EKS...). |
| **EKS** | AWS ka managed Kubernetes. |
| **RDS / MSK / ElastiCache** | Managed Postgres / Kafka / Redis. |
| **Cognito** | AWS ka managed auth (Keycloak ki jagah prod mein). |
| **IRSA** | Pod ko bina long-lived key ke AWS permission (IAM Role for Service Account). |
| **OIDC (GitHub→AWS)** | CI ko bina stored secret ke AWS access. |
| **External Secrets** | AWS Secrets Manager se secret cluster mein laana. |
| **Ansible** | VM pe cheezein install/config karne ka tool (K8s ke bina tarika). |

## F. CI/CD / supply chain — matlab
| Shabd | Ek line |
|---|---|
| **CI/CD** | Commit → auto test/build → auto deploy tak ka pipeline. |
| **Argo CD (GitOps)** | Git mein jo likha hai wahi cluster mein — sync karta rehta hai. |
| **Trivy** | Image/code mein known vulnerabilities scan. |
| **SBOM** | Image ke andar kya-kya packages hain uski list. |
| **cosign / signing** | Image pe digital signature → sirf trusted image chale (Kyverno verify). |
| **gitleaks** | Code mein galti se push hua secret pakadna. |
