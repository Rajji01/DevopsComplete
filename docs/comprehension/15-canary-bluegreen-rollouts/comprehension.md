# 15 · Shipping a new version: canary vs blue-green — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/rollouts/order-service-canary.yaml`, `inventory-service-bluegreen.yaml`, `analysis-template.yaml`, `rollouts/README.md`. Baseline: `k8s/base/*/deployment.yaml` (RollingUpdate).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's progressive-delivery design. Read once, then answer.*

The baseline deploy is a plain `Deployment` with `RollingUpdate`: every pod gets the new version within about a minute, and "is the new version healthy?" means only "its readiness probe passed." A bug that passes readiness but returns 5xx to real users would still roll out to everyone. ShopFlow keeps two **Argo Rollouts** strategies side by side for a safer rollout, each reusing the existing `Deployment`'s pod template via `workloadRef` (no copied config).

**Canary (order-service).** The new version takes traffic in steps: **10% → 25% → 50% → 100%**, with pauses between. After the first 10% step, an **AnalysisTemplate** runs: every minute it queries Prometheus for the **canary pods' 5xx ratio** (the query filters to canary pods via a label Rollouts injects), and requires it to stay **below 2%**. If analysis fails (the error ratio is too high for 3 consecutive measurements), the rollout **automatically aborts** — traffic shifts back and the old pods, which were still serving the other 90%, keep running. The new version is judged by **real metrics on real (but limited) traffic**, not just a probe. One caveat: exact traffic weights (10%, 25%) need a traffic router (ALB/NGINX/Istio); without one, the "weight" is approximated by the **ratio of pods**.

**Blue-green (inventory-service).** The new version ("green") is brought up **behind a separate preview Service** while the old ("blue") still serves **all** live traffic. `autoPromotionEnabled: false`, so a human (or automation) runs smoke tests against the preview first; a `prePromotionAnalysis` also checks the error ratio. On **promotion**, the active Service **flips to green in one step** — 0% to 100% instantly. Blue is **kept for 60 seconds** after promotion (`scaleDownDelaySeconds`), so rollback is **instant**: flip the active Service back to blue. The cost: during the preview you run **2× the pods**.

The trade is clear: **canary** limits *blast radius* (only 10% see a bad version) and decides by metrics, but is slower and (for exact weights) needs a traffic router; **blue-green** gives an **instant** switch and instant rollback and a clean preview environment, but every user moves at once (no partial exposure) and it costs double pods during the window.

---

## Questions

### A — Literal
1. In the baseline `RollingUpdate`, what does "healthy new version" actually mean, and why is that weak?
2. What are the canary's four traffic steps, and what decides whether it advances past the first one?
3. In blue-green, what serves live traffic while "green" is being tested, and how long is blue kept after promotion — and why?

### B — Inference
4. The canary analysis query filters to **canary pods only**. Why would measuring the error ratio across *all* pods (old + new) defeat the purpose of the canary?
5. Blue-green moves **all** users at once on promotion. Given that, what class of bug can a canary catch that blue-green's pre-promotion smoke test is likely to miss?

### C — Trade-off
6. Canary needs a traffic router (ALB/NGINX/Istio) for *exact* weights; without one the weight is the pod ratio. What does "weight ≈ pod ratio" mean for a service with only 3 pods trying to do a 10% canary?
7. Blue-green costs 2× pods during the preview and switches instantly; canary costs a few extra pods and switches gradually. For a **stateful-ish, latency-critical checkout path**, which property of each would you weigh most, and why isn't there a single right answer?

### D — Transfer / extension
8. You're rolling out a change that is only exercised by ~1% of traffic (a rare coupon path). Why might a **canary** at 10% give you false confidence, and what would you change about the rollout or analysis to actually test it?
9. A rollback is needed 30 seconds after a blue-green promotion vs 30 minutes after. Explain why the first is instant and the second may not be, citing `scaleDownDelaySeconds`.

### E — Spot-the-flaw
10. To "speed up releases," a dev sets the canary's `AnalysisTemplate` to `successCondition: result[0] < 0.5` (50% error ratio) and removes the pauses. The rollout now always goes straight to 100% and never aborts. What has the canary been reduced to, and what real incident will this now ship?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. It means only that the pod's **readiness probe passed** (process up, own DB reachable). It's weak because a version can be "ready" yet return **5xx to real users** (a logic bug, a bad downstream call) — readiness doesn't look at real request outcomes, so a broken-but-ready version rolls out to everyone.
2. **10% → 25% → 50% → 100%**, with pauses. After the first (10%) step, an **AnalysisTemplate** (canary 5xx ratio < 2% via Prometheus) decides whether it advances; failing analysis aborts.
3. **Blue** (the old version) serves **all** live traffic while **green** is tested behind a **preview** Service. Blue is kept **60 seconds** after promotion (`scaleDownDelaySeconds`) so rollback is **instant** — you just flip the active Service back to blue while it's still running.
</details>

<details>
<summary>B — Inference</summary>

4. The canary's whole point is to judge **the new version in isolation** on a small slice of traffic. If you averaged errors across old + new pods, the 90% of healthy old-version traffic would **dilute** the canary's errors — a new version erroring at 15% but only taking 10% of traffic would show as ~1.5% overall and pass the <2% gate, so you'd promote a broken version. Filtering to canary pods makes the signal about **the new version only**.
5. Bugs that only appear under **real production traffic, scale, or data variety** — a slow memory leak under load, a race under concurrency, an edge case in real user data, a performance regression visible only at production RPS. A pre-promotion smoke test hits green with **synthetic, low-volume** requests, so it can miss anything that needs real traffic; a canary exposes the new version to a real 10% slice and watches real metrics, catching those.
</details>

<details>
<summary>C — Trade-off</summary>

6. With no traffic router, Rollouts approximates weight by **pod count ratio**. With only 3 pods, the smallest non-zero canary is **1 of 3 ≈ 33%**, not 10% — you can't express a 10% canary at all without more pods or a real router. So "10% canary" on a small deployment either isn't really 10% (blast radius is larger than intended) or requires scaling up pod count / adding ALB/NGINX/Istio to split traffic precisely.
7. For latency-critical checkout: **canary's** key property is **limited blast radius + metric-based gating** (only 10% of checkouts risk the new bug, and error-ratio analysis auto-aborts) — great for catching a regression safely. **Blue-green's** key property is the **instant, atomic switch and instant rollback** (no half-and-half state, no version skew between pods mid-rollout) — great when a mixed fleet would be a problem. There's no single right answer because they optimize different fears: canary minimizes *how many* users hit a bad version; blue-green minimizes *how long* and avoids *mixed-version* exposure. The choice depends on which risk hurts your checkout more.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. At 10% traffic, a path used by only ~1% of requests might get **almost no canary hits**, so the analysis sees a clean error ratio and promotes — **false confidence**, because the risky code barely ran. Fixes: (a) **drive synthetic traffic** down the coupon path against the canary during the pause; (b) **extend the pause / lower the step** so more real traffic (and thus more coupon hits) accumulate before advancing; (c) add a **targeted metric** to the analysis for that path specifically (its own error/latency query), not just the overall 5xx ratio.
9. Blue is kept for `scaleDownDelaySeconds: 60`. At **30 seconds** post-promotion, blue pods are **still running**, so rollback is just flipping the active Service back to them — instant, no restart. At **30 minutes**, blue has long been scaled down, so "rollback" now means **re-deploying the old version** (pull image, start pods, wait for readiness) — minutes, not instant. The instant-rollback window is exactly the `scaleDownDelaySeconds` duration.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. With `< 0.5` (allow up to 50% errors) and no pauses, the analysis **can essentially never fail** and there's no time for metrics to accumulate — so the canary **goes straight to 100% unconditionally**. It's been reduced to a plain **RollingUpdate with extra machinery** — all the cost of Rollouts, none of the protection. It will now ship exactly what canary exists to stop: a version that's "ready" but erroring (anything under 50%, which is catastrophic) rolls out to **all** users with no gate and no automatic abort. The thresholds and pauses *are* the safety; removing them removes the point.
</details>

---

## Tod ke dekho — Local (minikube + Argo Rollouts)

Goal: watch a canary step through weights with analysis, and a blue-green preview→promote→rollback.

```bash
# minikube + Argo Rollouts controller + kubectl plugin
make k8s-dev                         # base deploments first
kubectl create ns argo-rollouts && kubectl apply -n argo-rollouts \
  -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml
kubectl apply -k shopflow/k8s/rollouts   # replaces the 2 Deployments with Rollouts

# CANARY (order-service): watch it step 10->25->50->100 with analysis pauses
kubectl argo rollouts get rollout order-service -n shopflow --watch
#   trigger a new version: kubectl argo rollouts set image order-service order-service=<new-tag> -n shopflow
#   if canary 5xx > 2% (analysis), it ABORTS automatically; old pods keep serving

# BLUE-GREEN (inventory-service): preview, smoke test, promote, (instant) rollback
kubectl argo rollouts get rollout inventory-service -n shopflow --watch
#   green comes up behind inventory-service-preview; test it:
kubectl -n shopflow port-forward svc/inventory-service-preview 18082:8082 &
curl -s localhost:18082/actuator/health/readiness | jq
kubectl argo rollouts promote inventory-service -n shopflow      # flip active -> green, instant
kubectl argo rollouts undo inventory-service -n shopflow         # within 60s -> instant rollback to blue
```

**Proved:** canary advances only while analysis passes and auto-aborts on errors (blast radius capped); blue-green keeps blue live behind a preview and flips atomically with a 60s instant-rollback window.

## Tod ke dekho — Online
Cluster chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section B (Cloud Shell + minikube)**. (Killercoda bhi, par Rollouts controller install karna padega.)

---

## Interview concepts is passage se
- **Progressive delivery** vs plain RollingUpdate ("ready" ≠ "correct").
- **Canary** — stepwise weight, **metric analysis (Prometheus)**, auto-abort, blast radius.
- **Blue-green** — preview Service, atomic flip, instant rollback, 2× pods.
- **AnalysisTemplate** — canary-pod-only error ratio < threshold, failureLimit.
- **`workloadRef`** — Rollout reuses Deployment's pod template (no copy).
- **Traffic router** (ALB/NGINX/Istio) — exact weights; warna weight ≈ pod ratio.
- **`scaleDownDelaySeconds`** — instant-rollback window.

**Interviewer's lens:** "canary vs blue-green kab" ka jawab trade-off ke saath (blast radius vs instant/atomic, metric-gated vs smoke-test), aur "analysis canary-pods-only kyu" — ye signatures hain.

> Interview line: *"RollingUpdate 'ready' ko 'healthy' maanta hai, par ready pod bhi 5xx de sakta. Isliye Argo Rollouts: canary (order-service) new version ko 10→25→50→100% deta, har step pe Prometheus se sirf canary pods ka 5xx ratio <2% check karta, fail pe auto-abort (blast radius capped). Blue-green (inventory) green ko preview Service ke peeche laata, human smoke-test karta, promote pe active Service ek step mein flip, aur blue 60s tak zinda rehta — instant rollback. Exact weights ke liye traffic router (ALB/NGINX/Istio) chahiye, warna weight ≈ pod ratio. Canary blast radius minimize karta, blue-green mixed-version aur rollback-time minimize karta."*
</content>
