# D11 · inventory had a blip, and suddenly order-service has zero endpoints

> Skill: Kubernetes health / cascading-failure debugging. Related mechanism: [`../comprehension/07-k8s-readiness-cascade/`](../comprehension/07-k8s-readiness-cascade/).

---

## Page (symptom)

inventory-service had a 90-second blip (a slow restart). During it, **order-service went fully down** — clients got `connection refused`, `kubectl get endpoints order-service` showed **no addresses**, and every order-service pod flipped to **NotReady at the same moment**. The order-service pods themselves were fine: their process was up, their own Postgres was reachable, no OOM, no crash. When inventory recovered, order-service came back. This never used to happen — a past inventory outage only made orders go `FAILED`, it didn't take order-service down.

**Before details: all pods NotReady at once, but the pods are healthy — what class of failure is this, and what one thing must have changed?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- All order-service pods: `Ready: False` simultaneously, each for the ~90s of the inventory blip; liveness never failed (no restarts).
- `kubectl describe pod` on an order pod: `Readiness probe failed: HTTP 503` from `/actuator/health/readiness`.
- `curl …/actuator/health/readiness` on an order pod during the blip: `{"status":"DOWN","components":{"inventory":{"status":"DOWN"}, "db":{"status":"UP"}, ...}}`.
- A deploy two days ago changed the readiness probe path from `/actuator/health/readiness` to `/actuator/health` (the **aggregate**), "to be thorough" — or, equivalently, added `inventory` to the readiness health group.
- order-service's own process + Postgres were healthy throughout; the circuit breaker to inventory was open (as designed).
</details>

---

## Your move

1. All pods NotReady at the exact same time, but each pod is individually healthy. Why does "simultaneous + healthy" rule out a per-pod problem and point at configuration?
2. Trace the sequence: inventory blips → what does each order-service readiness check now return → what does Kubernetes do → what do clients see?
3. What's the difference between `/actuator/health` (aggregate) and `/actuator/health/readiness` (the scoped group), and why does that one-line change cause this?
4. Before the change, the same inventory outage produced `FAILED` orders but no downtime. Why is that the *correct* behaviour, and what did the change trade away?
5. What's the fix, and what's the general rule for what belongs in a readiness probe?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Simultaneous + healthy = configuration, not a pod fault (Q1).** A per-pod problem (OOM, a bad node, a crash) hits pods **independently** and staggered. **Every** pod going NotReady at the **same instant**, while each pod's own process and DB are fine, means they're all reacting to **one shared external signal** through **identical config** — i.e. their readiness check is coupled to something outside the pod. That's a configuration/design issue, not an instance failure.

**The sequence (Q2).** inventory blips → each order-service pod's readiness check (now the **aggregate** `/actuator/health`, which includes a downstream `inventory` health indicator) returns **DOWN** → Kubernetes marks **every** order pod `NotReady` → it removes them **all** from the `order-service` Service endpoints → the Service has **no endpoints** → clients hit it and get **connection refused**. order-service is "down" even though it could have kept accepting orders. One dependency's blip became a full order-service outage: a **cascading failure**.

**Aggregate vs scoped (Q3, root cause).** `/actuator/health` (aggregate) is `DOWN` if **any** component is down — including Kafka, Redis, or a **downstream service** health indicator. `/actuator/health/readiness` is a **scoped group** configured as `readinessState, db` — only the app's own state and its **own** Postgres. Pointing readiness at the aggregate (or adding `inventory` to the group) makes a *downstream* outage fail *this* service's readiness. That single "to be thorough" change is the whole bug: readiness must answer "can **I** serve a request?", not "is the entire system perfect?".

**Why the old behaviour was correct (Q4).** With readiness scoped to own-deps, an inventory outage leaves order-service **Ready** and in rotation: it accepts the order, the inventory call fast-fails (breaker open), the order is saved `FAILED`, and the reconciler settles it later. The outage stays **contained** to degraded functionality, not downtime, and the pods are ready to serve the instant inventory returns. The "thorough" change traded that containment for a **cascade** — it coupled order-service's availability to inventory's, turning one outage into two. (Readiness reporting "ready while degraded" is a feature; you detect the degradation with *alerting* — breaker-open / `FAILED`-rate / inventory `ServiceDown` — not by failing readiness.)

**Fix (Q5):** point the readiness probe back at `/actuator/health/readiness` and keep the group `readinessState, db` — **own dependencies only**. General rule: a **readiness** probe includes only what the pod itself needs to serve *any* request (its process + its own datastore); a **downstream** service belongs in **neither** readiness nor liveness — you alert on it instead. (And a DB belongs in readiness but **never** liveness, or a DB blip would mass-restart every pod — see D04/topic 07.)
</details>

---

## The pattern

- **All replicas unhealthy at once + each replica fine = shared config, not instance failure.** Staggered = per-pod; simultaneous = something they all read the same way.
- **Readiness answers "can I serve?", not "is everything perfect?"** Coupling it to a downstream turns that dependency's outage into your outage — a cascade.
- **Aggregate health ≠ readiness.** `/actuator/health` is DOWN on any component; readiness must be a **scoped group** of own-deps. "To be thorough" is how this bug ships.
- **Containment beats completeness.** A service that stays up and degrades (FAILED orders + alerts) is better than one that goes fully down because a dependency hiccuped.
- **Correlate the flip with "what changed."** A readiness regression usually rides in on a recent probe/config edit; `kubectl describe` + the health JSON name the culprit component.

> Grounded in: `k8s/base/order-service/deployment.yaml` (readinessProbe → `/actuator/health/readiness`), `order-service application.yml` (`management.endpoint.health.group.readiness.include: readinessState, db`), the breaker/`FAILED`/reconciler path (D04, topic 04/07).
</content>
