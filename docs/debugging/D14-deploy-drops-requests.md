# D14 · Every deploy throws a few seconds of 502s / connection resets

> Skill: zero-downtime deploy debugging. Related mechanism: [`../comprehension/07-k8s-readiness-cascade/`](../comprehension/07-k8s-readiness-cascade/), [`../comprehension/15-canary-bluegreen-rollouts/`](../comprehension/15-canary-bluegreen-rollouts/).

---

## Page (symptom)

Since last week, **every** order-service rollout produces a short burst of **502 / connection reset** errors at the load balancer — a few seconds per pod as the rollout moves through them. Users retrying succeed. The SLO burn-rate alert even blipped during the last deploy. It didn't used to do this; deploys were clean. No code changed in the request path — only the Deployment manifest was "tidied up."

**Before details: a burst of 5xx *during* a rollout but fine otherwise points at the termination path, not the app. What has to be true for a pod shutdown to drop in-flight requests?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- Errors are **502 / connection reset**, not application 500s — the app never saw these requests complete; the connection died under them.
- They occur **only during rollouts**, clustered at each pod's termination, ~2–5s each. Steady state is clean.
- `maxUnavailable: 0`, `maxSurge: 1` are still set; readiness/liveness probes are intact; `terminationGracePeriodSeconds: 45` present; the app has `server.shutdown: graceful`.
- The "tidy-up" diff removed the container's `lifecycle.preStop` hook (`exec: sleep 5`) — "it's just a sleep that does nothing."
- A terminating pod's logs show it received SIGTERM and began graceful shutdown **immediately**, while the LB was still sending it new connections.
</details>

---

## Your move

1. The errors are **502/connection reset**, not app 500s. What does that distinction tell you about *where* the request died?
2. When Kubernetes deletes a pod during a rollout, two things happen "at once": SIGTERM to the container, and removal from the Service endpoints. Why is the **lack of ordering** between them the whole problem?
3. What did the removed `preStop: sleep 5` actually do, and why is "it's just a sleep that does nothing" exactly wrong?
4. `graceful shutdown` and `terminationGracePeriodSeconds: 45` are still there — why aren't they enough on their own to prevent the 502s?
5. What's the fix, and what's the correct **ordering** of events for a zero-downtime pod shutdown?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**502 ≠ 500 (Q1).** A 500 is the app running and returning an error — the request reached a live server. A **502 / connection reset** means the proxy/LB forwarded the request to a backend that **wasn't there** (or died mid-response) — the request never got a clean answer from a running app. So the failure is in **connection routing during shutdown**, not in the code. "During rollout only" confirms it: it's the **termination path**.

**The unordered race (Q2, root cause).** On pod deletion, Kubernetes does two things **concurrently and without ordering**: (a) the kubelet sends the container **SIGTERM**, and (b) the endpoints controller removes the pod from the Service's **endpoints**, which then has to propagate to kube-proxy / the LB / ingress. If (a) wins the race — the app starts shutting down **before** the LB has stopped routing to it — then new connections are still arriving at a socket that's closing → **connection resets / 502**. The removal propagation takes a beat; nothing inherently makes SIGTERM wait for it.

**What `preStop: sleep 5` did (Q3).** The `preStop` hook runs **before** SIGTERM. A `sleep 5` holds the pod in "Terminating" for 5 seconds **while it keeps serving**, giving the endpoints removal time to propagate to the LB so new traffic stops arriving — *then* SIGTERM fires and graceful shutdown drains the rest. It's not a no-op: it **buys the deregistration window**. Removing it lets SIGTERM win the race immediately → the exact 502 burst you see. ("It's just a sleep" is the classic misread — the sleep's *purpose* is the delay, which is the fix.)

**Why graceful shutdown alone isn't enough (Q4).** `server.shutdown: graceful` + the grace period handle the requests **already in flight** when SIGTERM arrives (they finish within the window). But they do **nothing** about the **new** connections that keep arriving in the gap between "SIGTERM" and "LB stopped routing" — those are rejected by a server that's now closing its listener. Graceful shutdown drains the *in-flight*; `preStop` prevents *new* arrivals during the drain. You need **both**: preStop to stop new traffic, graceful to finish old traffic.

**Fix + correct ordering (Q5).** Restore `lifecycle.preStop: exec: ["sh","-c","sleep 5"]`. The correct zero-downtime shutdown sequence then is: pod marked Terminating → **preStop sleep runs** *and* **endpoints removal propagates** (concurrently, the sleep covers the propagation) → LB stops sending new connections → **SIGTERM** → Spring **graceful shutdown** drains in-flight requests (bounded by `timeout-per-shutdown-phase` / `terminationGracePeriodSeconds: 45`) → process exits. New traffic stopped *before* the app stops; in-flight traffic finished. With `maxUnavailable: 0 / maxSurge: 1` adding a new Ready pod before removing an old one, the rollout then drops zero requests.
</details>

---

## Reproduce / test (apne haath se)
```bash
# minikube: show the preStop race. With a steady client hitting the Service during a rollout:
kubectl -n shopflow get deploy order-service -o yaml | grep -A3 preStop   # the sleep 5 that buys the dereg window
# remove the lifecycle.preStop block, apply, then: while true; do curl -s -o /dev/null -w "%{http_code} " <svc-url>; done
kubectl -n shopflow rollout restart deploy/order-service   # -> brief 502/connection-reset bursts per pod
# restore preStop -> clean rollout, no drops.
```

## The pattern

- **5xx only during a rollout = the termination path, not the app.** And **502/reset ≠ 500**: the request died at the connection, not in the code.
- **Pod shutdown is a race: SIGTERM vs endpoint deregistration, unordered.** If the app stops before the LB stops routing, you drop new requests.
- **`preStop` (stop new traffic) and graceful shutdown (finish old traffic) are two different jobs.** You need both; neither alone gives zero-downtime.
- **A `sleep` in `preStop` is load-bearing, not decoration.** Its value *is* the delay that covers deregistration propagation. "Tidying away" infra you don't understand is how clean deploys start dropping requests.
- **Zero-downtime = add-before-remove (`maxUnavailable: 0`) + stop-new (`preStop`) + finish-old (graceful).** All three, or you get the blip.

> Grounded in: `k8s/base/order-service/deployment.yaml` (`lifecycle.preStop: sleep 5`, `terminationGracePeriodSeconds: 45`, `maxUnavailable: 0`/`maxSurge: 1`), `order-service application.yml` (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 20s`).
</content>
