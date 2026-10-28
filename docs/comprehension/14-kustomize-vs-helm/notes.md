# 14 · Kustomize vs Helm — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/base/`, `k8s/overlays/{dev,prod}/`, `helm/shopflow-service/`, `helm/values/*`, `helm/README.md`.

## Kya (one line)
Manifests ko environment ke hisaab se package karne ke do tarike: **Kustomize** (base + overlays, copy+patch, no logic) aur **Helm** (ek chart + values files, template+logic) — ShopFlow dono side-by-side rakhta; vendor software Helm se, apni apps Kustomize/Helm-values se.

## Easy way (Hinglish analogy)
Shaadi card socho:
- **Kustomize** = ek **chhapaa hua base card** rakho, phir har function (dev/prod) ke liye uspe **sticker/patch** laga do (date, venue badal do). Jo dikh raha wahi chhap raha — saaf, par har naye rishtedaar ke liye poora naya base card banana padega.
- **Helm** = ek **template** jisme `{{naam}}`, `{{date}}` blanks hain, aur ek chhoti si **values sheet** jisse har card bhar lo. Ek template, N sheets — par template mein `if/loop` ghus jaaye to padhna mushkil.
Vendor ka card (ingress-nginx) aisa template hai jo hazaar logon ke liye banा — wahan logic zaroori. Apne 4 ek-jaise cards ke liye simple base kaafi.

## Kyu (ye problem solve karta hai)
Same service ko dev aur prod mein thoda alag chahiye (dev: in-cluster PG/Kafka; prod: RDS/MSK + External Secrets + IRSA + zyada replicas). Isko manage karne ke do approaches; dono ke apne trade-off:
- Kustomize no-logic = readable, par repetition (base per service).
- Helm templating = reuse, par `if/range` se invalid YAML / hidden-branch bugs / "actually kya deploy hoga" dekhne ke liye render karna pade.
Isliye team dono rakhti: jahan reuse chahiye (vendor, many services) Helm, jahan readability (apni handful apps) Kustomize.

## Kaise (step by step)
1. **Kustomize**: `base/<service>/` = full valid YAML (deployment/service/hpa/pdb). `overlays/dev` + `overlays/prod` patch karte (image, replicas, env, prod-only: `external-secret.yaml`, `service-accounts.yaml`). Prod image = commit SHA (CI se). `kubectl kustomize overlays/prod` render; Argo CD sync.
2. **Helm**: `helm/shopflow-service/` = ek chart (`templates/*` + `_helpers.tpl` + `values.yaml`). `helm/values/order-service.yaml` (+ `-prod.yaml`) per service/env. `helm template` render, `helm upgrade --install` apply, `helm rollback <rel> <rev>`.
3. **CI**: dono ko `kubeconform` se validate (`make validate`).

## Flow diagram (same service, two paths)
```
                     ┌─ Kustomize ─────────────────────────────────┐
 base/order-service  │  base (full YAML)                           │
   (one copy /svc)   │     + overlays/dev  patch ─► dev manifests   │──► Argo CD sync ─► cluster
                     │     + overlays/prod patch (SHA, ExtSecret) ─►│
                     └─────────────────────────────────────────────┘
                     ┌─ Helm ──────────────────────────────────────┐
 helm/shopflow-service (one chart)                                  │
     + values/order-service.yaml ───────► render ─► manifests ──────│──► helm upgrade ─► cluster
     + values/order-service-prod.yaml ──►                           │   (helm history / rollback)
                     └─────────────────────────────────────────────┘
```

## Decision diagram (kab kaunsa)
```
 Third-party software (ingress-nginx, External Secrets, Argo CD)?  → HELM (it's a chart)
 Your own apps, few, nearly identical, readability first?           → KUSTOMIZE
 Your own apps, many copies of base/<service> (≥4)?                 → HELM chart (one + values each)
 GitOps rollback?                                                   → git revert + Argo CD sync
                                                                      (or helm rollback per release)
```

## Alternate approaches (kab kaunsa)
| | Kustomize | Helm |
|---|---|---|
| Model | copy + patch (overlays) | template + values |
| Reuse (N services) | base copied per service | one chart, N values |
| Logic | none (readable, repetitive) | if/range/helpers (powerful, harder) |
| Rollback | git history + `rollout undo` | `helm history`/`rollback` per release |
| Third-party | awkward | the standard |
| ShopFlow | `k8s/` (Argo CD syncs this) | `helm/` (comparison) |

## Understanding check
1. Kustomize/Helm ka model ek phrase mein?
2. 4 services serve karne mein kya duplicate hota har tool mein?
3. No-logic readable **aur** repetitive dono kyu?
4. Vendor chart ke liye templating zyada kyu matter karta?
5. base vs overlay mein kya-kya hona chahiye?

## Interview mein ye bolo
*"Kustomize copy+patch — base + dev/prod overlays, no templating, isliye readable par base per service copy; prod overlay image ko commit SHA pe pin karta aur Argo CD wahi sync karta (GitOps, rollback = git revert + resync). Helm template+values — ek generic chart + per-service ~15-line values, if/range/helpers, helm rollback per release; third-party software (ingress-nginx, External Secrets) Helm se hi aata. Hum dono use karte — vendor Helm se, apni apps Kustomize ya Helm-values se. Rule of thumb: base/<service> chauthi baar copy karo to chart banao."*

> Related: [`../15-canary-bluegreen-rollouts/`](../15-canary-bluegreen-rollouts/) (deploy strategy), [`../08-supply-chain-signing/`](../08-supply-chain-signing/) (SHA pin → prod overlay).
</content>
