# D15 · The autoscaler keeps scaling up and down every couple of minutes

> Skill: autoscaling debugging. Related mechanism: [`../comprehension/05-overselling-concurrency/`](../comprehension/05-overselling-concurrency/) (per-pod limits), [`../comprehension/15-canary-bluegreen-rollouts/`](../comprehension/15-canary-bluegreen-rollouts/) (HPA owns replicas).

---

## Page (symptom)

order-service's replica count is **oscillating**: 2 → 4 → 2 → 5 → 3, changing every couple of minutes, all day, even when traffic is steady. Each scale-down terminates pods (and D14-style, occasionally blips a few requests); each scale-up cold-starts JVMs. Cost is up, p99 is jittery, and the deploy dashboard looks like a heartbeat monitor. Traffic itself is **flat** — no real load spikes.

**Before details: flapping (rapid up/down) with *flat* traffic is a tuning problem, not a load problem. What governs how fast an HPA adds and removes pods?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- HPA: `minReplicas: 2`, `maxReplicas: 6`, metric `cpu` `averageUtilization: 70` (% of the CPU **request**, which is `250m`).
- `kubectl describe hpa order-service`: CPU hovering **right around 70%** — crossing above and below every scrape.
- The JVM has **bursty CPU**: the `@Scheduled` outbox relay (every 1s), the reconciler (every 60s), and GC cause short CPU spikes even at steady request load.
- A manifest "cleanup" last week removed `behavior.scaleDown.stabilizationWindowSeconds: 300` ("looked like clutter").
- Each scale event is clean HPA arithmetic — nothing is erroring; it's the churn itself that's the problem.
</details>

---

## Your move

1. Traffic is flat but replicas oscillate. Why does a metric "hovering around the target" cause flapping, and why is CPU an especially twitchy metric for a JVM?
2. HPA scale-up and scale-down are deliberately **asymmetric** by default. What's the asymmetry, and why does it exist?
3. The removed setting was `scaleDown.stabilizationWindowSeconds: 300`. What did it do, and how does its absence produce exactly this pattern?
4. Would raising the CPU target from 70% to 85% "fix" it? What would that trade?
5. What's the fix, and what's one better *metric* to scale on for a request-driven service than raw CPU?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Why "around the target" flaps (Q1).** An HPA scales toward keeping the metric at the target. When CPU **hovers right at 70%**, each scrape randomly reads a bit above (→ scale up) or a bit below (→ scale down). For a **JVM**, CPU is especially noisy: GC pauses, JIT, and the `@Scheduled` relay/reconciler cause **short CPU bursts** unrelated to request load, and the target is a % of a **small** request (`250m`), so a brief burst easily crosses 70%. So the input signal oscillates even though *traffic* is flat — and without damping, the replica count oscillates with it.

**The built-in asymmetry (Q2).** By default HPA scales **up fast** (react quickly to real load, protect users) and scales **down slow** (a stabilization window, default 300s, before removing pods). The asymmetry is deliberate: the cost of scaling **up** late is a user-facing overload; the cost of scaling **down** late is just a few extra pods for a while. So it errs toward keeping capacity — removing pods only after the metric has stayed low for the window. Scale-up has no such long window, so it's responsive.

**What the removed window did (Q3, root cause).** `scaleDown.stabilizationWindowSeconds: 300` makes the HPA, before removing pods, look at the **max desired replicas over the last 5 minutes** — so a brief dip in CPU doesn't immediately trigger a scale-down. Removing it drops that damping: now a momentary dip below 70% **immediately** removes a pod, CPU per remaining pod rises, a momentary spike **immediately** adds one, and so on — a feedback loop with no stabilization = **flapping**. The config that was "clutter" was the anti-flap mechanism.

**Raising the target to 85% (Q4)?** It would reduce flapping a bit (fewer scale-ups) but for the **wrong reason** and with a cost: you'd run each pod **hotter** before adding capacity, so a real traffic spike has less headroom and p99 suffers under load. It treats the symptom (crossing the threshold) by moving the threshold, not the cause (no damping). Prefer restoring the stabilization window over masking with a hotter target.

**Fix + a better metric (Q5).** Restore `behavior.scaleDown.stabilizationWindowSeconds: 300` (and optionally a short `scaleUp` stabilization + a `scaleDown` percentage/pod cap so it removes pods gently). That stops the flap directly. **Better metric:** for a request-driven service, scale on something that tracks **actual demand** rather than noisy CPU — e.g. **requests-per-second per pod** or **concurrent in-flight requests** (a custom/external metric), or a queue depth for a consumer. CPU is a *lagging, bursty* proxy for load; a request-rate metric is smoother and more directly what you're trying to keep per-pod. (CPU is the easy default — it just needs the stabilization window to behave.)
</details>

---

## The pattern

- **Flapping + flat traffic = a tuning/damping problem, not a load problem.** The metric is oscillating around the target; the autoscaler faithfully chases it.
- **HPA is asymmetric on purpose: up fast, down slow.** The scale-down stabilization window is the anti-flap damping — removing it is how steady traffic starts churning pods.
- **CPU is a twitchy signal for a JVM.** GC + scheduled jobs + a small CPU request make brief bursts cross the target; request-rate/concurrency metrics track demand more smoothly.
- **Raising the target masks, it doesn't fix.** It trades headroom for fewer scale events — the wrong lever when the real issue is missing damping.
- **"Clutter" in an HPA `behavior` block is usually load-bearing.** Stabilization windows look like noise and *are* the thing keeping it calm.

> Grounded in: `k8s/base/order-service/hpa.yaml` (`minReplicas: 2`/`maxReplicas: 6`, CPU `averageUtilization: 70`, `behavior.scaleDown.stabilizationWindowSeconds: 300`), the `@Scheduled` relay/reconciler (bursty CPU), `deployment.yaml` CPU request `250m`. Pairs with D14 (each scale-down is a termination).
</content>
