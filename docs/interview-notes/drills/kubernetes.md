# Kubernetes — interview drill set (ShopFlow edition)

> **Ye kya hai:** ek **question-only drill sheet** — basic se prod tak. Study notes `../05-kubernetes.md` mein hain (sabse zyada poocha jaane wala topic); ye wo set hai jise **ek-ek karke mock-interview** ki tarah drill karna.
>
> **Tags:** `[C]` = core · `[SF]` = ShopFlow se defend · `[P]` = prod / failure scenario.

## ShopFlow mein k8s kahan (grounding)

- **Manifests:** `shopflow/k8s/base/` + overlays (**Kustomize** base + overlays), aur **Helm** chart `shopflow/helm/shopflow-service` (dono jaan-bujh ke — do approaches).
- **Deployment:** `RollingUpdate maxSurge=1 maxUnavailable=0`, `revisionHistoryLimit=5`.
- **Probes:** `startupProbe` + `livenessProbe` + `readinessProbe` → `/actuator/health/liveness` & `/actuator/health/readiness`.
- **Security:** `runAsNonRoot`, `runAsUser/Group 10001`, `seccompProfile RuntimeDefault`, `allowPrivilegeEscalation=false`, `readOnlyRootFilesystem`, `capabilities drop: [ALL]`, `automountServiceAccountToken=false`, `/tmp` emptyDir.
- **Scheduling/HA:** `topologySpreadConstraints` (spread across nodes), HPA (Helm `fail` guard: `replicas` **ya** `hpa.enabled`, dono nahi — Walk 68).
- **Graceful shutdown:** `terminationGracePeriodSeconds=45`, `preStop: sleep 5`.
- **Policy:** **Kyverno + Gatekeeper** (no-root, resources-required type policies).
- **Delivery:** **Argo Rollouts** (canary) + **Argo CD** (GitOps).
- **Observability:** `prometheus.io/scrape` annotations → `/actuator/prometheus`.

---

## TIER 1 — Fundamentals
1. Pod vs Deployment vs ReplicaSet — teeno ka rishta? `[C]`
2. Service kya hai aur kyun (Pod IP badalti rehti)? ClusterIP / NodePort / LoadBalancer farq? `[C]`
3. ConfigMap vs Secret — farq aur ShopFlow mein kya kahan (DB_URL vs DB_PASSWORD)? `[SF][C]`
4. Namespace kis kaam? `[C]`
5. `kubectl get/describe/logs` — ek crashed pod ko debug karne ka order? `[P]`
6. Labels & selectors — Service apne Pods kaise dhoondti? ShopFlow `selectorLabels` helper kya karta? `[SF][C]`
7. ShopFlow ka ek service k8s pe deploy hone se traffic lene tak ka flow batao. `[SF]`

## TIER 2 — Probes & lifecycle (bahut poocha)
8. **startup vs liveness vs readiness** — teeno ka farq, ek-ek ka example? `[SF][C]`
9. ShopFlow readiness mein downstream (inventory/Redis) **kyun nahi** include karta? (cascading failure) `[SF][P]`
10. Liveness probe galat set ho to kya disaster? (healthy pod baar-baar restart) `[P]`
11. **Graceful shutdown** — `SIGTERM` → `preStop sleep 5` → `terminationGracePeriodSeconds 45`. Ye sequence kyun, in-flight request kaise bachti? `[SF][P]`
12. Rolling update ke dauraan ek request kyun drop ho sakti hai, aur `maxUnavailable=0` + readiness + preStop usse kaise rokte? `[SF][P]`

## TIER 3 — Resources, scaling, scheduling
13. `requests` vs `limits` — farq, aur galat set karne ke do classic bugs (OOMKill, CPU throttle)? `[P][C]`
14. **QoS classes** (Guaranteed/Burstable/BestEffort) — eviction order pe asar? `[P]`
15. **HPA** kaise kaam karta? ShopFlow mein `replicas` aur HPA dono set karne pe kya hota (aur Helm `fail` guard kyun)? `[SF][P]`
16. HPA ke liye metrics kahan se? CPU ke alawa custom metric (e.g. Kafka lag) pe scale kaise? `[P]`
17. **topologySpreadConstraints** kya karta? ShopFlow mein kyun (ek node gira to saare pod na jaayein)? `[SF][P]`
18. **PodDisruptionBudget** kis problem ko rokta (node drain ke waqt)? `[P]`
19. Taints/tolerations aur nodeAffinity — ek- line use-case? `[C]`

## TIER 4 — Config, security, networking
20. Secret base64 hai, encrypted nahi — production mein sach mein kaise secure (etcd encryption, external secrets, sealed-secrets)? `[P]`
21. ShopFlow ka `securityContext` padho — `runAsNonRoot`, `readOnlyRootFilesystem`, `drop: [ALL]`, `automountServiceAccountToken: false` — har ek kyun? `[SF][P]`
22. **Kyverno / Gatekeeper (OPA)** kya karte? ShopFlow kaunsi policy enforce karta aur kyun admission-time pe? `[SF][P]`
23. NetworkPolicy kya karti (default sab khula)? `[P]`
24. Service-to-service DNS — ShopFlow `inventory-service` naam kaise resolve hota (CoreDNS, `svc.namespace.svc.cluster.local`)? `[SF][C]`
25. Ingress vs Service — bahar se traffic kaise aata? `[C]`

## TIER 5 — Deployment strategy & GitOps
26. RollingUpdate vs Recreate vs Blue-Green vs Canary — ShopFlow kaun use karta (Argo Rollouts) aur kyun? `[SF][C]`
27. **Argo Rollouts canary** kaise kaam karta — traffic step-by-step, analysis, auto-rollback? `[SF][P]`
28. **Argo CD / GitOps** — "git = source of truth" ka matlab, drift detect/self-heal kaise? `[SF][C]`
29. `kubectl apply` (imperative-ish) vs GitOps pull model — farq aur fayda? `[P]`
30. **Kustomize** base + overlays — ShopFlow env-specific config kaise handle karta vs Helm? (dono kyun rakhe) `[SF][C]`

## TIER 6 — Prod / failure scenarios (yahan jeet ya haar)
31. Pod **CrashLoopBackOff** — step-by-step kaise debug? `[P]`
32. Pod **Pending** reh gaya — kya-kya wajah (resources, taints, PVC, node)? `[P]`
33. **ImagePullBackOff** — causes aur fix? `[P]`
34. Node down ho gaya — Deployment ke pods ka kya hota, kitni der mein reschedule? `[P]`
35. **OOMKilled** pod — JVM heap vs container limit, kaise root-cause aur fix? `[SF][P]`
36. Ek deploy ke baad latency badh gayi — rollback kaise, aur `revisionHistoryLimit=5` kaise madad karta? `[SF][P]`
37. Readiness pass par traffic error de raha — kya galat ho sakta (probe path/logic)? `[P]`
38. Ek namespace resource kha raha baaki ko bhukha — **ResourceQuota / LimitRange** kaise? `[P]`
39. Secret rotate karna hai bina downtime — kaise? `[P]`
40. Cluster upgrade ke dauraan app available kaise rakhte (PDB + spread + rolling)? `[SF][P]`

---

### Drill log
| # | Topic | Attempted | Confident |
|---|---|---|---|
| 1–7 | Fundamentals | ☐ | ☐ |
| 8–12 | Probes & lifecycle | ☐ | ☐ |
| 13–19 | Resources/scaling/scheduling | ☐ | ☐ |
| 20–25 | Config/security/net | ☐ | ☐ |
| 26–30 | Deploy & GitOps | ☐ | ☐ |
| 31–40 | Prod failures | ☐ | ☐ |

> Tip: Probes (8–12) + CrashLoop/OOM/Pending debug (31–35) — ye do cluster "platform engineer" rounds ka 50% hain. Inhe reflex banao.
