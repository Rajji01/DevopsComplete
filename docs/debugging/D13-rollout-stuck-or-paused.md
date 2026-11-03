# D13 · The deploy has been "in progress" for 20 minutes — is it stuck?

> Skill: progressive-delivery / release debugging. Related mechanism: [`../comprehension/15-canary-bluegreen-rollouts/`](../comprehension/15-canary-bluegreen-rollouts/).

---

## Page (symptom)

Someone shipped a new order-service version 20 minutes ago. The release still shows **"in progress"**; `kubectl get pods` shows **both** the old and new versions running, and traffic looks split. People are nervous: "is the deploy stuck? should we roll back? kill it?" Nothing is erroring for users — checkout works. But the pipeline hasn't gone green and the on-call is twitchy.

**Before you touch anything: a progressive rollout that's "slow" is not automatically "stuck." What do you check to tell the difference, and why is reaching for rollback the wrong first move?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- order-service is an **Argo Rollout** with a **canary** strategy: steps `10% → pause 5m → 25% → pause 5m → 50% → pause 10m → 100%`, with an `AnalysisTemplate` (canary 5xx ratio must stay < 2%).
- `kubectl argo rollouts get rollout order-service -n shopflow` shows: **Status: Paused**, step `setWeight: 50`, pause `10m` with ~4m remaining; **Analysis: Successful** (canary error ratio 0.3%).
- Old ReplicaSet still has pods (serving the other 50%); new ReplicaSet healthy, all Ready.
- No alerts firing; canary and stable both report normal RED metrics.
- (Alternate timeline to consider: the same command instead shows **Status: Degraded**, Analysis **Failed**, traffic back to 100% stable.)
</details>

---

## Your move

1. The rollout says **Paused** at 50% with analysis **Successful** and ~4m left on a 10m pause. Is it stuck? What is it actually doing?
2. How do you distinguish, from one command, between **Paused** (healthy, by design), **Degraded/aborted** (analysis failed), and genuinely **stuck** (something's wrong)?
3. Why is "it's taking 20 minutes, roll it back" the wrong instinct here — what would you be undoing?
4. In the **alternate timeline** (Status: Degraded, Analysis: Failed, traffic at 100% stable), what already happened automatically, and what's left for a human to do?
5. If you genuinely need it live *now* (a hotfix), what's the safe way to finish the rollout, and what do you give up by doing it?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → "fix"</summary>

**Is it stuck? No — it's paused on purpose (Q1).** The canary strategy has **deliberate pauses** between weight steps so that real metrics accumulate on real (but limited) traffic before promoting further. "Paused at 50%, analysis Successful, 4 minutes left on a 10-minute pause" is the rollout **working exactly as designed** — it's *waiting*, not *stuck*. The new version is taking half the traffic and passing its error-ratio gate; in ~4 minutes it'll advance to 100%. The "20 minutes" is the sum of the configured pauses (5m + 5m + 10m), not a hang.

**Telling the three apart from one command (Q2).** `kubectl argo rollouts get rollout` shows the **Status** and the **current step + analysis result**:
- **Paused**, analysis **Successful/Running**, a pause timer counting down → **healthy, by design.** Do nothing; let it finish (or `promote` to skip the wait if you're confident).
- **Degraded**, analysis **Failed**, traffic shifted **back to stable** → the rollout **auto-aborted** itself because the canary breached the gate. Not stuck — *stopped on purpose*.
- Genuinely **stuck** looks different: `Progressing` with pods **not** becoming Ready (image pull error, failing probes, insufficient quota) or an analysis stuck **Inconclusive** because Prometheus is unreachable. Here you debug the pods/analysis provider, not the rollout logic.
The status line is the whole diagnosis; don't guess from "it's been a while."

**Why rollback is the wrong instinct (Q3).** Rolling back here would **abort a healthy release that's minutes from done** — you'd undo a version that's passing its metrics, for no reason other than impatience, and re-run the whole gauntlet next time. The pauses exist precisely so humans *don't* make a snap call; trust the gate. Reaching for rollback on a Paused-and-passing rollout defeats the point of having analysis gates at all.

**The alternate timeline (Q4).** Status **Degraded** + Analysis **Failed** + traffic back at 100% stable means the rollout **already rolled itself back** — the `AnalysisTemplate` saw the canary's 5xx ratio breach 2% and **auto-aborted**, shifting all traffic to the old (stable) pods, which were serving the other 90% all along. No customer-facing outage. What's left for a human: **investigate why the new version failed its gate** (read the canary's logs/traces for the window), fix it, and ship again — the automation handled the *safety*, you handle the *cause*.

**If you need it live now (Q5).** `kubectl argo rollouts promote order-service` skips the remaining pause and advances the canary (promote once = next step; `--full` = straight to 100%). That's safe-ish because analysis is passing — but you **give up** the remaining soak time, so a problem that only shows at higher traffic or over a longer window won't be caught by the gate you just skipped. For a true emergency you accept that trade knowingly; you don't skip gates by reflex. (Blue-green's equivalent is `promote` flipping the active Service; its `scaleDownDelaySeconds` keeps the old version for instant rollback.)
</details>

---

## Reproduce / test (apne haath se)
```bash
# minikube + Argo Rollouts
make k8s-dev && kubectl create ns argo-rollouts
kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml
kubectl apply -k shopflow/k8s/rollouts
kubectl argo rollouts get rollout order-service -n shopflow --watch   # read Status: Paused vs Degraded vs Progressing
# trigger a version: kubectl argo rollouts set image order-service order-service=<tag> -n shopflow
# promote past a pause (if analysis is passing): kubectl argo rollouts promote order-service -n shopflow
```

## The pattern

- **"Slow" is not "stuck" for a progressive rollout.** Pauses and analysis windows are *designed* latency; read the status before reacting.
- **One command, three states:** Paused+passing (let it ride), Degraded+failed (it already rolled back — go find the cause), Progressing-but-pods-not-Ready / analysis-Inconclusive (now it's genuinely stuck — debug pods/metrics provider).
- **Don't fight your own safety gates.** Rolling back a healthy, passing canary out of impatience throws away the entire reason you added analysis.
- **Auto-abort does the safety; humans do the diagnosis.** A Degraded rollout already protected users; your job is why the canary failed, not to re-trigger the rollback.
- **Skipping a gate (`promote`) is a conscious trade, not a shortcut.** You trade soak time for speed; fine in a real emergency, reckless as a habit.

> Grounded in: `k8s/rollouts/order-service-canary.yaml` (steps + pauses + analysis), `analysis-template.yaml` (error-ratio < 2%, auto-abort), `inventory-service-bluegreen.yaml` (`promote`, `scaleDownDelaySeconds`). Tooling: `kubectl argo rollouts get/promote`.
</content>
