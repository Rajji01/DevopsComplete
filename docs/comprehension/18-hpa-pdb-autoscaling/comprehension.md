# 18 · Scaling to load, surviving a node drain: HPA + PDB — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/base/order-service/hpa.yaml`, `pdb.yaml`, `deployment.yaml` (no `spec.replicas`, `maxUnavailable: 0`).
> **Debug it live:** [D15](../../debugging/D15-hpa-flapping.md) · [D14](../../debugging/D14-deploy-drops-requests.md) — isi mechanism ka symptom-se-root-cause drill.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's capacity + availability notes. Read once, then answer.*

order-service has to do two different things that sound similar but aren't: **scale with load**, and **stay available when the cluster shuffles pods around**. Three objects divide that work, and they must not fight each other.

**HPA (HorizontalPodAutoscaler)** owns the replica *count*. It scales order-service between `minReplicas: 2` and `maxReplicas: 6` to keep CPU at `averageUtilization: 70%` of each pod's CPU **request**. It scales **up fast** and **down slow** (a `scaleDown.stabilizationWindowSeconds: 300`, so it waits 5 minutes of sustained low CPU before removing pods — otherwise it would flap). Because the HPA owns the count, the Deployment has **no `spec.replicas`** — hard-coding one there would fight the autoscaler on every `kubectl apply`.

**PDB (PodDisruptionBudget)** protects availability during **voluntary disruptions** — things an operator or the platform *chooses* to do: draining a node for maintenance, a cluster upgrade, a scale-down of the node pool. order-service's PDB says `minAvailable: 1`: a drain may evict its pods, but Kubernetes will **not** let a voluntary disruption take the count below **1 running** pod at a time. Without it, a node drain could evict **all** order-service pods at once (if they happen to share a node), causing an outage during routine maintenance.

The crucial distinction: a PDB **only** guards **voluntary** disruptions. An **involuntary** disruption — a node hardware failure, an OOMKill, a kernel panic — is **not** throttled by the PDB; Kubernetes can't negotiate with a crash. For those, availability comes from **running enough replicas spread across nodes** (the Deployment's `topologySpreadConstraints`) so losing one node doesn't lose everything.

The three compose: the **Deployment** defines the pod template and rollout strategy (`maxUnavailable: 0, maxSurge: 1` — add a ready pod before removing one, for zero-downtime deploys); the **HPA** sets how many; the **PDB** limits how many can be voluntarily taken down at once. HPA is about *load*, PDB is about *disruption*, and the rollout strategy is about *deploys* — three different axes, three objects.

---

## Questions

### A — Literal
1. What does the HPA scale on, between what bounds, and why does the Deployment have no `spec.replicas`?
2. What does `minAvailable: 1` guarantee, and against which class of disruption?
3. Name a disruption the PDB does **not** protect against, and what provides availability there instead.

### B — Inference
4. The HPA scales up fast but down slow (5-minute stabilization). Why the asymmetry — what's the cost of being wrong in each direction?
5. A node is drained during a deploy. How do the PDB (`minAvailable: 1`) and the rollout strategy (`maxUnavailable: 0`) each contribute to no requests being dropped?

### C — Trade-off
6. `minAvailable: 1` is a weak budget — it allows going down to a single pod during a drain. What's the trade-off of `minAvailable: 1` vs something stricter like `minAvailable: 50%`, especially given the HPA's `minReplicas: 2`?
7. HPA, PDB, and `spec.replicas` all influence "how many pods". Why is it important that exactly **one** of them (the HPA) owns the count, and what breaks if two do?

### D — Transfer / extension
8. During an incident, someone runs `kubectl scale deploy/order-service --replicas=1` to "save resources". Twenty minutes later it's back at 3. What happened, and what should they have changed instead?
9. A node pool upgrade needs to drain 3 nodes, and all 2 order-service pods are on node A. Walk through what the PDB makes the drain do, step by step, so the service stays up.

### E — Spot-the-flaw
10. To "guarantee we never lose capacity", a dev sets the PDB to `minAvailable: 100%`. A week later, a routine node drain for a security patch **hangs forever** and the cluster upgrade is blocked. Explain why, and what a correct budget looks like here.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. The HPA scales on **CPU utilization (70% of each pod's CPU request)**, between **`minReplicas: 2` and `maxReplicas: 6`**. The Deployment has **no `spec.replicas`** because the **HPA owns the count** — setting replicas on the Deployment would reset it on every `kubectl apply` and fight the autoscaler.
2. `minAvailable: 1` guarantees that at least **1 order-service pod stays running** during a **voluntary** disruption (Kubernetes won't voluntarily evict below that).
3. A **node hardware failure / OOMKill / kernel panic** (an **involuntary** disruption) — the PDB doesn't throttle those. Availability there comes from **enough replicas spread across nodes** (`topologySpreadConstraints`), so losing a node doesn't lose the service.
</details>

<details>
<summary>B — Inference</summary>

4. Being slow to scale **up** means users hit an **overloaded** service (latency/errors) — expensive and visible. Being slow to scale **down** just means running a few **extra pods** for a while — cheap. So the asymmetry errs toward keeping capacity: react fast to real load, remove pods only after CPU has stayed low for 5 minutes (which also prevents **flapping** on a metric that hovers near the target).
5. The **PDB** (`minAvailable: 1`) makes the node drain evict order-service pods **one at a time**, never taking the last one down until a replacement is Ready elsewhere — so the Service always has an endpoint. The **rollout strategy** (`maxUnavailable: 0, maxSurge: 1`) ensures that when pods are replaced, a **new Ready pod is added before an old one is removed**, so desired capacity is never undercut. Together: the drain never empties the service, and replacements never dip below capacity → no dropped requests.
</details>

<details>
<summary>C — Trade-off</summary>

6. `minAvailable: 1` is permissive: during a drain the service can run on a **single** pod briefly — less capacity, but drains/upgrades proceed quickly. `minAvailable: 50%` (or `maxUnavailable: 1`) keeps more capacity up during disruptions but makes drains **slower** (fewer pods can move at once) and, combined with a low replica count, can **block** a drain if it can't satisfy the budget. With `minReplicas: 2`, `minAvailable: 1` means "at most one pod disrupted at a time" — a reasonable balance for a service that also spreads across nodes; a latency-critical service with more replicas might choose a percentage budget for more headroom during maintenance.
7. Replica count is a **single piece of mutable state**; if two controllers both set it, they **fight** — e.g. the HPA scales to 4 for load, then a `kubectl apply` with `spec.replicas: 2` resets it to 2, the HPA scales back to 4, and you get thrashing plus confusing behaviour (and every GitOps sync re-fights). Exactly one owner (the HPA) means the count has a single source of truth; the Deployment deliberately omits `replicas` so it never contradicts the autoscaler.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. The **HPA owns the replica count**, so `kubectl scale` is a one-off manual override that the HPA **overwrites** on its next reconcile (scaling back toward what CPU demands, min 2) — hence it's back at 3 twenty minutes later. To actually reduce capacity they'd change the **HPA** (`maxReplicas`, or the CPU target), or scale on a different metric — not the Deployment/`kubectl scale`, which the HPA ignores.
9. Both pods are on node A; PDB is `minAvailable: 1`. The drain on node A tries to evict both, but the PDB allows **only one** to be disrupted at a time (it must keep ≥1 available). So: evict pod-1 → the Deployment/HPA schedules a replacement on another node (B/C) → **wait until that replacement is Ready** → now ≥1 is available elsewhere, so the PDB permits evicting pod-2 → it reschedules onto a surviving node too. The drain proceeds **one pod at a time**, never below 1 available, so the Service always has an endpoint. (This is also why spreading replicas across nodes up front makes drains smoother.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `minAvailable: 100%` means **no** pod may be voluntarily disrupted — ever. A node drain needs to evict the pods on that node, but the PDB forbids taking **any** below 100% available, so the eviction can **never** satisfy the budget and the drain **blocks indefinitely** (and with it the cluster upgrade / patch). A PDB is meant to **bound** disruption, not forbid it; `100%` turns a safety limit into a deadlock. A correct budget leaves room for at least one pod to move — `minAvailable: 1` (or `maxUnavailable: 1`, or a percentage < 100%) — so maintenance can proceed one pod at a time while keeping the service up. (For a single-replica workload, no PDB can give both "always up" and "drainable" — you need ≥2 replicas first.)
</details>

---

## Tod ke dekho — Local (minikube)

```bash
make k8s-dev          # dev overlay (needs metrics-server for HPA: minikube addons enable metrics-server)
kubectl -n shopflow get hpa order-service          # shows targets/min/max, current replicas
kubectl -n shopflow get pdb order-service          # ALLOWED DISRUPTIONS column

# PDB in action: cordon+drain the node the pods are on, watch evictions happen one at a time
kubectl -n shopflow get pods -o wide               # see which node
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data   # PDB keeps >=1 up; drain waits for replacements
kubectl -n shopflow get pods -w                    # a new pod goes Ready before the last old one is evicted

# HPA in action: generate CPU load, watch it scale up (fast) then down (after ~5m stabilization)
kubectl -n shopflow get hpa order-service -w
```

**Proved:** the PDB makes a drain evict pods one at a time (ALLOWED DISRUPTIONS respects `minAvailable: 1`); the HPA moves the replica count on CPU and holds it for 5 minutes before scaling down. `kubectl scale` gets reverted by the HPA.

## Tod ke dekho — Online
Cluster → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section B (Cloud Shell + minikube)** (enable metrics-server for the HPA).

---

## Interview concepts is passage se
- **HPA** — scale on a metric (CPU%) between min/max; up-fast/down-slow (stabilization) to avoid flap.
- **HPA owns replicas** — no `spec.replicas`; one owner for the count.
- **PDB** — `minAvailable`/`maxUnavailable` bounds **voluntary** disruptions (drain/upgrade).
- **Voluntary vs involuntary disruption** — PDB guards voluntary; replicas + spread guard involuntary.
- **Three axes, three objects** — Deployment (deploy strategy), HPA (load), PDB (disruption).
- **`minAvailable: 100%` = drain deadlock** — a budget must permit *some* disruption.

**Interviewer's lens:** HPA (load) vs PDB (disruption) ka clear farak, voluntary vs involuntary, aur "kaun replicas ka maalik" (HPA — isliye `kubectl scale` revert ho jaata). `minAvailable:100%` deadlock classic gotcha.

> Interview line: *"Teen alag cheezein, teen object: Deployment deploy-strategy (maxUnavailable:0/maxSurge:1), HPA load pe scale (CPU 70%, 2–6, up-fast down-slow stabilization — isliye `spec.replicas` nahi, warna autoscaler se ladega), aur PDB voluntary disruptions (node drain/upgrade) pe `minAvailable:1` — drain ek-ek pod evict kare, service kabhi khaali na ho. PDB sirf voluntary guard karta; node crash (involuntary) ke liye replicas + topology spread. `minAvailable:100%` mat karo — drain hamesha ke liye block ho jaata."*
</content>
