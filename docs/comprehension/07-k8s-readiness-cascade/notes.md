# 07 · Probes, readiness scoping & cascading failure — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/base/order-service/deployment.yaml`, `order-service application.yml` (health group).

## Kya (one line)
Readiness probe sirf **apni khud ki dependency** (process + apna DB) check kare — downstream (inventory/Redis/Kafka) nahi — warna ek service ka outage saare pods ko load balancer se nikaal ke doosra, bada outage bana deta hai (cascade). Liveness=restart, readiness=traffic roko.

## Easy way (Hinglish analogy)
Dukaan ka shutter socho. **Readiness** = "kya main grahak le sakta hoon abhi?" **Liveness** = "kya main zinda hoon ya behosh?" (behosh → ambulance/restart).
Ab galti: tumne shutter ko supplier se jod diya — "agar supplier ne maal nahi bheja to shutter band." Supplier ek din band hua → **saari dukaanein ek saath band** ho gayi, jabki tum to purana stock bech sakte the. Ek supplier ki dikkat se poora market band. Isiliye shutter sirf **apni** cheez pe depend kare (bijli + apna cash counter), supplier pe nahi.

## Kyu (ye problem solve karta hai)
- **Do alag sawaal, do probe:** "stuck ho? → restart" (liveness) aur "traffic bhejun? → endpoints se add/remove" (readiness). Inhe mix karna = galat action (DB blip pe restart, ya recoverable pod ko maarna).
- **Cascade rok:** readiness sirf un cheezon pe jo pod ko *koi bhi* request serve karne ke liye chahiye (process + own DB). Downstream down pe pod rotation mein rehta, fail-fast karta (circuit breaker), order `FAILED`, reconciler baad mein — ek outage contained.
- **DB readiness mein (traffic roko) par liveness mein nahi (warna mass-restart).**

## Kaise (step by step)
1. **startupProbe** `/health/liveness`, `period 2s × failureThreshold 30` = ~60s JVM ko warm hone ka time; tab tak liveness judge nahi.
2. **livenessProbe** `/health/liveness`, fail×3 → container restart.
3. **readinessProbe** `/health/readiness`, fail×3 → endpoints se remove (restart nahi).
4. **Health group** (`application.yml`): `management.endpoint.health.group.readiness.include: readinessState, db`. Sirf own state + own Postgres. Inventory/Redis/Kafka nahi.
5. **Zero-downtime:** `maxUnavailable:0` + `maxSurge:1` (pehle naya pod add phir purana hatao) + readiness gate + `preStop: sleep 5` (endpoints se nikalte waqt in-flight drain) + `terminationGracePeriodSeconds:45` + Spring `shutdown: graceful`.
6. **No `spec.replicas`** → HPA count ka maalik; warna har apply autoscaler se ladega.

## Flow diagram (cascade: galat vs sahi)
```
 GALAT (readiness includes inventory):
   inventory DOWN ─► har order pod readiness FAIL ─► K8s saare order pods NotReady
                  ─► order-service Service endpoints EMPTY ─► clients ko connection fail
                  ─► 1 outage ➜ 2 outages (CASCADE)

 SAHI (readiness = readinessState + db only):
   inventory DOWN ─► order pod readiness STILL UP ─► traffic aata rehta
                  ─► inventory call fail-fast (circuit breaker) ─► order = FAILED
                  ─► reconciler later ─► CANCELLED           (outage CONTAINED)
   order's OWN postgres DOWN ─► readiness DOWN ─► traffic ruka (sahi) ; pod NOT restarted
```

## Probe decision diagram
```
 Signal fail → kya karna hai?
   process stuck/hung?        → LIVENESS fail → RESTART
   can't serve right now?     → READINESS fail → STOP TRAFFIC (no restart)
   still booting?             → STARTUP grace → judge mat karo abhi
 Dependency rule:
   own process + own DB  → readiness (stop traffic when truly can't serve)
   downstream service    → NEITHER probe → alert instead (ServiceDown / breaker-open / lag)
   DB in LIVENESS         → NEVER (DB blip = mass restart)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Readiness = own deps only** *(ShopFlow)* | `readinessState,db` | no cascade, contained outage | pod "ready" but degraded → alag alert chahiye | user-facing services |
| **Readiness = aggregate /health** | sab components | "thorough" | **cascade** on any dep outage | kabhi nahi (user-facing) |
| **No readiness, only liveness** | sirf alive check | simple | half-warm pods ko traffic, rollout glitch | trivial apps |
| **maxUnavailable:0 + maxSurge:1** | surge then retire | zero-downtime | spare capacity + slower | latency-sensitive |
| **maxUnavailable:1 + maxSurge:0** | retire then add | fast, no spare | dips below capacity | batch/non-critical |

## Understanding check
1. Liveness vs readiness — K8s ka action kya?
2. Readiness group mein kya include, kya exclude, aur kyu?
3. Readiness mein inventory daal do to cascade ka exact sequence?
4. DB ko liveness mein kyu kabhi nahi daalte?
5. `maxUnavailable:0,maxSurge:1` ka cost kya hai?

## Interview mein ye bolo
*"Liveness fail = restart, readiness fail = traffic roko. order-service ki readiness sirf apna state aur apna Postgres (`readinessState,db`) check karti — inventory/Redis/Kafka nahi — warna downstream outage pe saare pods NotReady ho ke endpoints se nikal jaate, ek outage do ban jaata (cascade). Downstream down pe hum order FAILED mark karke servable rehte, reconciler settle karta. DB readiness mein hai par liveness mein kabhi nahi (DB blip = mass restart). Zero-downtime: maxUnavailable:0 + maxSurge:1 + preStop sleep + graceful shutdown; replicas HPA ke paas, isliye spec.replicas nahi."*

> Related: [`../04-order-limbo-reconciler/`](../04-order-limbo-reconciler/) (FAILED handling jab downstream down), [`../05-overselling-concurrency/`](../05-overselling-concurrency/) (HPA/per-pod).
</content>
