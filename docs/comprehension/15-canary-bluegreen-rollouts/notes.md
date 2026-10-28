# 15 · Canary vs blue-green (Argo Rollouts) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/rollouts/order-service-canary.yaml`, `inventory-service-bluegreen.yaml`, `analysis-template.yaml`, `rollouts/README.md`.

## Kya (one line)
Plain RollingUpdate "ready" ko "healthy" maan leta hai; isliye progressive delivery: **canary** (new version ko dheere 10→25→50→100% traffic, har step pe metric analysis, fail pe auto-abort) aur **blue-green** (green ko preview Service ke peeche test karo, phir active Service ek step mein flip, instant rollback).

## Easy way (Hinglish analogy)
Naya khana menu pe daalna hai:
- **RollingUpdate** = seedha sabko naya khana parosa do; sirf itna dekha ki "plate aa gayi" (readiness). Khana kharab nikla to sab bimaar.
- **Canary** = pehle **10 logon** ko do, dekho kitne bimaar (5xx <2%?), theek to 25, 50, phir sab. Kharab laga to turant band — baaki 90% ne purana hi khaya tha.
- **Blue-green** = naya khana ek **alag table (preview)** pe banao, khud chakho, theek lage to **switch** — ab sab ko naya. Pasand na aaye to purana **60 sec tak ready pada hai**, turant wapas.

## Kyu (ye problem solve karta hai)
"Ready" ka matlab sirf probe pass — par ready pod bhi real users ko 5xx de sakta (logic bug, bad downstream). RollingUpdate aise bug ko sab tak pahuncha deta. Progressive delivery do dar kam karti:
- **Canary** → *kitne* users bad version dekhein (blast radius) kam, aur **real metrics** se decide (probe se nahi).
- **Blue-green** → *kitni der* / mixed-version exposure kam, aur rollback **instant**.
Alag dar, alag tool — isliye ek "best" nahi.

## Kaise (step by step)
1. **workloadRef**: dono Rollouts base Deployment ka pod template reuse karte (`scaleDown: onsuccess`) — config copy nahi.
2. **Canary** (`order-service`): `steps` 10/pause5m /25/pause5m /50/pause10m /100. `analysis: error-ratio`, `startingStep:1` (10% ke baad). `maxSurge:1,maxUnavailable:0`.
3. **AnalysisTemplate** (`error-ratio`): har 1m, count 5, `successCondition result[0] < 0.02`, `failureLimit 3`. Prometheus query sirf canary pods (`rollouts_pod_template_hash != ""`). Fail → auto-abort, purane pods serve karte rehte.
4. **Blue-green** (`inventory-service`): `activeService` + `previewService`, `autoPromotionEnabled:false` (human smoke test), `prePromotionAnalysis`, `scaleDownDelaySeconds:60` (instant rollback window).
5. **Weights**: exact ke liye traffic router (ALB/NGINX/Istio); warna weight ≈ pod ratio.

## Flow diagram (canary vs blue-green)
```
 CANARY (order-service):
   v2 @ 10% ──analysis(5xx<2%?)──► 25% ──►50% ──►100%
      │ fail (3×)                                   old v1 pods served other 90% all along
      ▼ AUTO-ABORT → back to v1

 BLUE-GREEN (inventory-service):
   blue(v1) serves 100%   green(v2) behind preview Service ──smoke test + prePromotionAnalysis
      │                                   │ promote (human)
      ▼ active Service flips blue→green (0→100% atomic)
   blue kept 60s (scaleDownDelaySeconds) → undo = flip back (INSTANT)
```

## Comparison diagram
```
 property          RollingUpdate     Canary                 Blue-green
 ──────────────────────────────────────────────────────────────────────
 traffic to new    all (by pod, ~1m) 10→25→50→100 stepwise  0% then 100% atomic
 decide by         readiness only    Prometheus error ratio human smoke + analysis
 rollback          rollout undo (~1m) auto-abort (metrics)  instant (flip Service, 60s)
 blast radius      everyone          10% first              everyone (after flip)
 extra cost        1 pod (surge)     few canary pods        2× pods during preview
 needs             nothing           Rollouts + Prometheus  Rollouts
                                     (+router for weights)
```

## Alternate approaches (kab kaunsa)
| Strategy | Best jab | Avoid jab |
|---|---|---|
| **RollingUpdate** | simple apps, good tests | ready≠correct risk high |
| **Canary** *(order-service)* | blast radius minimize, metric-gated, real-traffic bugs | exact weights bina router + chhota pod count |
| **Blue-green** *(inventory-service)* | instant/atomic switch, no mixed-version, instant rollback | pod budget tight (2×) |

## Understanding check
1. RollingUpdate mein "healthy" ka matlab kya, weak kyu?
2. Canary analysis sirf canary pods pe kyu (all pods pe kyu nahi)?
3. Blue-green promote ke baad blue kitni der, kyu?
4. 3 pods pe 10% canary kyu mushkil?
5. Canary vs blue-green — kaunsa dar kaun minimize karta?

## Interview mein ye bolo
*"RollingUpdate 'ready' ko 'healthy' maanta, par ready pod bhi 5xx de sakta. Isliye Argo Rollouts: canary (order-service) new version ko 10→25→50→100% deta, har step pe Prometheus se sirf canary pods ka 5xx ratio <2% check karta (all pods pe karte to 90% healthy traffic dilute kar deta), 3 baar fail pe auto-abort — purane pods serve karte rahe. Blue-green (inventory) green ko preview Service ke peeche laata, human smoke test, promote pe active Service atomic flip, blue 60s zinda (scaleDownDelaySeconds) = instant rollback. Exact weights ko traffic router chahiye warna weight ≈ pod ratio. Dono workloadRef se Deployment template reuse karte. Canary blast radius kam karta, blue-green mixed-version aur rollback-time."*

> Related: [`../14-kustomize-vs-helm/`](../14-kustomize-vs-helm/) (packaging), [`../06-slo-burn-rate-bug/`](../06-slo-burn-rate-bug/) (the metrics analysis reads), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (readiness vs real health).
</content>
