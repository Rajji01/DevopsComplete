# 18 · HPA + PDB (autoscaling & disruption budget) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/base/order-service/hpa.yaml`, `pdb.yaml`, `deployment.yaml`.

## Kya (one line)
**HPA** load pe replica count scale karta (CPU% pe, min–max, up-fast/down-slow), **PDB** voluntary disruptions (node drain/upgrade) pe `minAvailable` se availability bachata — teen alag axes, teen object: Deployment (deploy), HPA (load), PDB (disruption).

## Easy way (Hinglish analogy)
Dukaan socho. **HPA** = bheed ke hisaab se kitne kaunter kholne — bheed badhi to jaldi aur kholo (up-fast), bheed kam hui to 5 min ruk ke band karo (down-slow, warna baar-baar khol-band = flapping). **PDB** = jab building maintenance ho (voluntary — tum decide karte), ek saath saare kaunter band mat karo, kam se kam 1 chalu rahe. **Par agar bijli chali jaye (involuntary — crash)**, PDB kuch nahi kar sakta — uske liye kaunter alag-alag floors (nodes) pe rakho.

## Kyu (ye problem solve karta hai)
Do alag zarooratein jo milti-julti lagti: (a) load ke saath scale, (b) cluster jab pods idhar-udhar kare tab available raho. Inhe alag objects sambhalte, aur unhe aapas mein ladna nahi chahiye:
- HPA bina stabilization ke flap karta; bina single-owner ke count pe do controller lad-te.
- PDB na ho to ek node drain saare pods ek saath uda sakta (maintenance pe outage).
- PDB galat (100%) ho to drain hamesha block.

## Kaise (step by step)
1. **HPA** (`hpa.yaml`): `minReplicas:2`, `maxReplicas:6`, CPU `averageUtilization:70` (request ka %), `behavior.scaleDown.stabilizationWindowSeconds:300`. Count ka **maalik** — isliye Deployment mein `spec.replicas` nahi.
2. **PDB** (`pdb.yaml`): `minAvailable:1` — **voluntary** disruption (drain/upgrade) ek-ek pod evict kare, ≥1 chalu.
3. **Involuntary** (node crash/OOM) PDB guard nahi karta → `topologySpreadConstraints` se replicas nodes pe spread.
4. **Deployment** rollout: `maxUnavailable:0`+`maxSurge:1` (naya Ready pod pehle, phir purana hatao).
5. **Interaction:** drain pe PDB + rollout milke zero-drop (ek-ek evict, replacement Ready hone ke baad).

## Flow diagram (node drain with PDB)
```
 both pods on node A, PDB minAvailable=1
 drain node A:
   evict pod-1 ──► schedule replacement on node B ──► wait Ready
        (PDB: only 1 disrupted at a time; >=1 must stay available)
   now >=1 available elsewhere ──► evict pod-2 ──► reschedule node C
   → drain proceeds ONE pod at a time, service never empty
```

## Three-axes diagram
```
 axis            object        knob                         guards
 ───────────────────────────────────────────────────────────────────
 load            HPA           min/max, CPU 70%, stabilization   overload / waste
 disruption      PDB           minAvailable: 1                   voluntary (drain/upgrade)
 deploy          Deployment    maxUnavailable:0 / maxSurge:1     rollout dips
 (involuntary: node crash → replicas + topologySpread, NOT PDB)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **HPA (CPU)** *(ShopFlow)* | scale on CPU% | simple, built-in | CPU twitchy (D15) | general |
| **HPA (custom/RPS)** | requests-per-pod metric | tracks demand smoother | needs metrics adapter | request-driven |
| **VPA** | right-size requests | fits pods to usage | conflicts w/ HPA on CPU | sizing, not scaling |
| **PDB minAvailable: N / %** | bound voluntary disruption | keep capacity in drains | too high = drain blocks | ≥2 replicas |
| **No PDB** | — | simplest | drain can empty service | non-critical |

## Understanding check
1. HPA kis pe scale, bounds kya, `spec.replicas` kyu nahi?
2. `minAvailable:1` kya guarantee karta, kis disruption ke against?
3. Voluntary vs involuntary — PDB kaunsa guard karta, doosre ke liye kya?
4. `kubectl scale --replicas=1` 20 min baad wapas kyu badh gaya?
5. `minAvailable:100%` se kya hota, sahi budget kya?

## Interview mein ye bolo
*"Teen axes, teen object: Deployment deploy-strategy (maxUnavailable:0/maxSurge:1), HPA load pe (CPU 70%, 2–6, up-fast down-slow stabilization — count ka maalik, isliye spec.replicas nahi warna autoscaler se ladega), PDB voluntary disruptions (node drain/upgrade) pe minAvailable:1 — drain ek-ek pod evict kare, service khaali na ho. PDB sirf voluntary guard karta; node crash (involuntary) ke liye replicas + topologySpread. kubectl scale HPA revert kar deta. minAvailable:100% mat karo — drain block ho jaata."*

> Related: [`../15-canary-bluegreen-rollouts/`](../15-canary-bluegreen-rollouts/) (HPA owns replicas), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/). Debug: [`../../debugging/D15-hpa-flapping.md`](../../debugging/D15-hpa-flapping.md), [`../../debugging/D14-deploy-drops-requests.md`](../../debugging/D14-deploy-drops-requests.md).
</content>
