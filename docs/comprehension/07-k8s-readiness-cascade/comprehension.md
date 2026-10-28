# 07 · Why readiness must not check the database — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/base/order-service/deployment.yaml` (probes, `maxUnavailable: 0`, `preStop`), `order-service application.yml` (`management.endpoint.health.group.readiness`).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's Kubernetes health-check design. Read once, then answer.*

Kubernetes asks a pod two different questions with two different probes. **Liveness**: "are you alive, or stuck?" — a failure makes Kubernetes **restart** the container. **Readiness**: "should I send you traffic right now?" — a failure makes Kubernetes **stop routing** to the pod (pull it from the Service endpoints) but **does not restart** it. A third, **startup**, gives a slow-booting JVM up to ~60s before liveness starts judging it, so a cold start isn't mistaken for a hang.

order-service exposes Spring Boot's `/actuator/health/liveness` and `/actuator/health/readiness`. The readiness group is configured deliberately narrowly:

```yaml
management.endpoint.health.group.readiness.include: readinessState, db
```

It includes the app's own state **and its own Postgres**, and **nothing else** — specifically **not** inventory-service, not Redis, not Kafka. The comment in the config is blunt: *"only own dependencies: if inventory is down, order pods must NOT be pulled from the load balancer — that would turn one outage into two."*

The reasoning: imagine readiness also pinged inventory-service. When inventory has an outage, **every order-service pod's readiness fails at once**, Kubernetes pulls them all from the Service, and now order-service is "down" too — even though it can still accept orders, mark them `FAILED`, and recover them later via the reconciler. One dependency's outage would **cascade** into a second, larger outage. By scoping readiness to only what the pod *itself* needs to serve any request at all (its process + its own DB), a downstream outage stays contained: calls to inventory fail fast (circuit breaker) and the order is marked `FAILED`, but the pod stays in rotation.

Deployments are configured for **zero downtime**: `maxUnavailable: 0` (never drop below desired capacity during a rollout), `maxSurge: 1`, a readiness probe so a new pod gets traffic only once ready, a `preStop` hook that `sleep`s ~5s so in-flight requests drain while the pod is removed from endpoints, and `terminationGracePeriodSeconds: 45` with Spring's graceful shutdown so SIGTERM lets in-flight requests finish. There is **no `spec.replicas`** — the HPA owns the count, so hard-coding replicas would fight the autoscaler on every apply.

---

## Questions

### A — Literal
1. State the Kubernetes action for a failed **liveness** probe versus a failed **readiness** probe.
2. Exactly which dependencies does order-service's readiness group include, and which named ones does it deliberately exclude?
3. What is the stated reason there is no `spec.replicas` on the Deployment?

### B — Inference
4. If readiness **did** include inventory-service, describe the sequence that turns a single inventory outage into an order-service outage.
5. A pod's liveness is healthy but its readiness is failing for 3 minutes. What is Kubernetes doing with that pod, and what is it **not** doing — and give one realistic cause.

### C — Trade-off
6. Scoping readiness to "only own DB" means order-service keeps serving even when inventory is down (returning `FAILED` orders). What's the upside of staying in rotation, and what's the honest downside the team accepts by not failing readiness?
7. `maxUnavailable: 0` with `maxSurge: 1` guarantees capacity during a deploy but changes rollout behaviour. What does this combination cost compared to `maxUnavailable: 1, maxSurge: 0`?

### D — Transfer / extension
8. notification-service consumes Kafka but serves no user-facing HTTP traffic. Should **its** readiness probe include Kafka? Argue both sides using the cascade principle, and state where you'd land.
9. You add a liveness check that queries Postgres. During a 30-second Postgres blip, what unintended, destructive thing can this liveness check trigger across all pods — and why is "DB in readiness, never in liveness" the rule?

### E — Spot-the-flaw
10. A dev sets the readiness probe to hit `/actuator/health` (the **aggregate** health endpoint) instead of `/actuator/health/readiness`, "to be thorough." The aggregate includes Kafka and Redis health. What production behaviour does this reintroduce, and why did it pass a quick local smoke test?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Liveness fail → Kubernetes restarts the container.** **Readiness fail → Kubernetes stops routing traffic to the pod (removes it from Service endpoints) but does not restart it.**
2. Includes **`readinessState`** (the app's own state) and **`db`** (its own Postgres). Deliberately **excludes** inventory-service, Redis, and Kafka.
3. The **HPA owns the replica count**; setting `spec.replicas` would reset the count on every `kubectl apply` and fight the autoscaler.
</details>

<details>
<summary>B — Inference</summary>

4. inventory-service goes down → every order-service pod's readiness check (which now pings inventory) fails → Kubernetes marks **all** order pods NotReady and pulls them from the Service endpoints → the order-service Service has **no endpoints** → clients get connection failures → order-service is now "down" too. One outage became two (a cascade), even though order-service could have kept accepting orders and marked them `FAILED`.
5. Kubernetes has **removed the pod from the Service endpoints** (no traffic reaches it) but is **not restarting** it (liveness is fine, so the process isn't considered stuck). Realistic cause: its own Postgres connection is failing (readiness includes `db`), or the app is briefly overloaded/warming a cache — a condition where restarting wouldn't help, but withholding traffic until it recovers does.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Upside:** the outage stays contained — order-service keeps accepting requests, marks inventory-dependent orders `FAILED`, and the reconciler settles them later; a downstream failure doesn't become a bigger user-facing failure, and the pods are ready to serve the instant inventory returns. **Downside the team accepts:** the pod reports "ready" while a chunk of its functionality is degraded (orders are failing), so readiness alone won't tell you inventory is broken — you need **separate alerting** (circuit-breaker-open / `FAILED`-rate / inventory `ServiceDown`) to surface the real problem. Readiness answers "can I serve at all," not "is everything perfect."
7. `maxUnavailable: 0, maxSurge: 1` **adds a pod before removing one**, so it needs **extra capacity/scheduling headroom** (one more pod's resources must be available) and the rollout is **slower** (it can only move one pod at a time without ever dipping below desired count). `maxUnavailable: 1, maxSurge: 0` is faster and needs no spare capacity, but it **runs below desired capacity** during the rollout — fine for batch workloads, risky for a latency-sensitive service under load.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **For including Kafka:** notification-service is useless without Kafka, so a pod that can't reach the broker arguably shouldn't be "ready." **Against (cascade principle):** readiness controls **HTTP traffic routing**, and notification-service serves no HTTP traffic that depends on readiness — failing readiness wouldn't protect any caller, it would just flap the pod out of a Service nothing routes to, and if *all* pods fail readiness during a Kafka blip you lose them from any Service/monitoring for no benefit. **Where I land:** keep Kafka **out** of readiness; use liveness only for the process, and rely on **consumer-lag alerting** (`KafkaConsumerLagHigh`) to detect a broker problem. Readiness is about "route traffic to me?"; a pure consumer has little traffic to gate.
9. If liveness queries Postgres, a 30-second Postgres blip makes **every pod's liveness fail at once**, so Kubernetes **restarts all of them simultaneously** — a mass restart (potential CrashLoop) that destroys warm caches/connections and can make recovery worse, all for a DB problem a restart can't fix. "DB in readiness, never in liveness" because a DB outage should **stop traffic** (readiness) to pods that can't serve, not **kill and restart** pods (liveness) that are perfectly alive and will recover when the DB does.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The aggregate `/actuator/health` is `DOWN` whenever **any** component — including Kafka or Redis — is down. Pointing readiness at it means a Kafka or Redis outage now fails **all** order-service pods' readiness and pulls them from the Service — reintroducing exactly the **cascade** the narrow `readiness` group was designed to prevent. It passed a local smoke test because, locally, Kafka/Redis/DB were all up, so the aggregate returned `UP` and readiness looked identical to the scoped group — the difference only shows during a *dependency* outage, which the smoke test didn't simulate.
</details>

---

## Tod ke dekho — Local (minikube) / docker compose

Goal: prove that taking a *downstream* dependency down does **not** flip order-service's readiness.

```bash
# --- docker compose quick proof ---
make up && TOKEN=$(make -s token)
curl -s http://localhost:8081/actuator/health/readiness | jq            # UP
docker compose -f shopflow/docker-compose.yml stop inventory-service
sleep 5
curl -s http://localhost:8081/actuator/health/readiness | jq            # STILL UP (inventory not in the group)
# an order now fails fast but the service stays servable:
curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' | jq '.status'
# now take its OWN db down -> readiness SHOULD drop:
docker compose -f shopflow/docker-compose.yml stop postgres
sleep 8
curl -s http://localhost:8081/actuator/health/readiness | jq            # DOWN (db IS in the group)

# --- minikube: see endpoints drop only for the right reason ---
make k8s-dev
kubectl -n shopflow get pods
kubectl -n shopflow get endpoints order-service          # lists ready pod IPs
kubectl -n shopflow scale deploy inventory-service --replicas=0
kubectl -n shopflow get endpoints order-service          # order-service endpoints UNCHANGED (no cascade)
```

**Proved:** downstream outage (inventory) → order-service stays Ready and in endpoints; own-DB outage → readiness correctly drops. That's the cascade guard, live.

> Zero-downtime drill: `kubectl -n shopflow rollout restart deploy/order-service` and watch `kubectl get pods -w` — a new pod goes Ready before an old one leaves (`maxUnavailable: 0`), and `preStop sleep 5` drains in-flight requests.

## Tod ke dekho — Online
k8s cluster chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section B (Google Cloud Shell + minikube)** ya **section C (Killercoda)**. Compose-only proof section A (Codespaces) pe.

---

## Interview concepts is passage se
- **Liveness vs readiness vs startup** — restart vs stop-traffic vs grace.
- **Cascading failure** — downstream health ko readiness mein daalne ka khatra.
- **Health group scoping** (`readinessState,db` only) — "own dependencies only."
- **DB in readiness, never in liveness** — outage traffic roke, pods na maare.
- **Zero-downtime rollout** — `maxUnavailable:0`+`maxSurge:1`+readiness+`preStop`+graceful shutdown.
- **HPA owns replicas** — `spec.replicas` mat do.
- **Readiness ≠ "sab perfect"** — degraded-but-servable; alag alerting chahiye.

**Interviewer's lens:** kya tu cascade ko sequence se bol pata hai, aur "DB readiness mein par liveness mein kabhi nahi" ka reason (mass-restart) samajhta hai. Zero-downtime knobs ka trade-off bhi.

> Interview line: *"Liveness fail = restart, readiness fail = traffic roko (restart nahi). order-service ki readiness sirf `readinessState` aur apna `db` check karti hai — inventory/Redis/Kafka nahi — kyunki agar readiness mein downstream hota, to inventory down hone pe saare order pods NotReady ho ke load balancer se nikal jaate: ek outage do ban jaata (cascade). Downstream down pe hum order FAILED mark karke servable rehte, reconciler baad mein settle karta. DB readiness mein hai par liveness mein kabhi nahi, warna DB blip pe saare pods ek saath restart. Zero-downtime ke liye maxUnavailable:0 + maxSurge:1 + preStop sleep + graceful shutdown, aur replicas HPA ke paas."*
</content>
