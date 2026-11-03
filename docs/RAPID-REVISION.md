# RAPID REVISION — ek page, interview se pehle (sab compressed)

> Deep: [`comprehension/`](comprehension/README.md) (mechanism + test), [`debugging/`](debugging/README.md) (symptom→fix), [`ARCHITECTURE.md`](ARCHITECTURE.md), [`HLD`](HLD-SYSTEM-DESIGN.md)/[`LLD`](LLD-DESIGN-PATTERNS.md). Ye sirf **yaad taaza** karne ke liye.

## 1. Har concept — ek line + "interview mein bolo"
| # | Concept | Ek line (yaad rakho) |
|---|---|---|
| 01 | **Outbox / dual-write** | DB+Kafka atomically nahi → order+event **1 Postgres txn** mein `outbox_event`, relay (1s, SKIP LOCKED, ack-wait) publish; **at-least-once → consumer idempotent**. |
| 02 | **Saga / refund** | Local txns + compensation (no 2PC). PAID cancel = **refund saga** (REFUND_PENDING→REFUNDED), **money pehle, stock baad**; decline → CAPTURED rehta, PaymentRefundFailed, alert+retry. |
| 03 | **Idempotency ×3** | client **Idempotency-Key** (ownership!) · consumer **processed_event**(eventId, same-txn) · payment **PK=eventId** · refund **by-state**. Natural key use karo. |
| 04 | **Limbo / reconciler** | Remote call ke around txn mat rakho (pool!). FAILED/PENDING→reconciler **auto-cancel** (idempotent release); CONFIRMED stuck→**sirf gauge+alert** (paisa move ho sakta). |
| 05 | **Overselling** | check+decrement **1 atomic SQL** `UPDATE…WHERE quantity>=:qty` (row-lock serialize); rows=0=out. **Stock kabhi cache se nahi.** 50/s/pod rate-limit. |
| 06 | **SLO burn-rate** | 99.5%/1s; multi-window (14.4×@1h+5m→page). Bug: `le=1.0` bucket prod mein nahi → alert dead though test green → fix `distribution.slo 1s` + guard test. |
| 07 | **Readiness** | liveness=restart, readiness=traffic-stop. Group = **own deps only** (`readinessState,db`); downstream daalo → **cascade**. DB readiness mein, liveness mein kabhi nahi. |
| 08 | **Supply chain** | Trivy→SBOM→cosign **keyless** (workflow OIDC, no key)→Kyverno verifyImages admission; **mutateDigest** (tag-swap rok); subject pinned to `…@main`. |
| 09 | **Resilience** | timeout(1s/2s) + retry(**transient only**, 3×) + breaker(50%/10→10s fast-fail); 409 na retry na count. Open→FAILED (readiness UP). Hanging call ko **sirf timeout** bachata. |
| 10 | **OAuth2/IDOR** | resource server, JWT: JWKS/RS256/exp/iss/**aud**. 401=auth-layer, 403=authz. Foreign order **404 not 403** (id hide). |
| 11 | **Kafka DLT/lag** | partition-order, consumer≤partitions. Poison pill → retry→**DLT** (partition flow). Lag=business delay. Exactly-once (DB/email) = **at-least-once+idempotent**. |
| 12 | **Caching** | cache-aside (DTO), write=**evict** (not update), stock kabhi cache se nahi, **fail-open** (Redis down→DB), Redis readiness se bahar. |
| 13 | **Tracing** | trace-id entry pe, propagate HTTP `traceparent` + **Kafka headers** (`observation-enabled`). Logs ECS JSON `trace.id`; Tempo↔Loki pivot. Prod 10% sample. |
| 14 | **Kustomize vs Helm** | copy+patch (base+overlay, readable) vs template+values (reuse). Vendor=Helm. Base 4th copy → chart. GitOps (Argo CD). |
| 15 | **Canary/blue-green** | "ready"≠"healthy". Canary 10→25→50→100 + metric analysis (5xx<2%) auto-abort (blast-radius); blue-green atomic flip + instant rollback. |
| 16 | **Virtual threads** | Java 21, blocking IO pe **unmount**, millions cheap, simple blocking code. Pinning (synchronized+IO→ReentrantLock). Real limit = **DB pool 5/pod**, CPU-bound nahi. |
| 17 | **ArchUnit** | agreements → build-failing tests (controller↛repo/@Entity, **service↛web** so Kafka-callable, no field-inject/System.out). Social→mechanical. |
| 18 | **HPA + PDB** | HPA=load (CPU70%, 2–6, up-fast/down-slow); PDB=**voluntary** disruption (drain) minAvailable:1; involuntary→replicas+spread. HPA owns replicas. |
| 19 | **Backups RPO/RTO** | RPO=data-loss(past), RTO=downtime(future). dev pg_dump ~24h → prod RDS PITR ~5min. Atomic `.part→.dump` (corrupt backup worse than none). Untested=hope. |
| 20 | **EKS / IRSA** | pod→AWS **no keys**: SA→role, SA-token→STS AssumeRoleWithWebIdentity→temp creds. Least-privilege (`*Topic*` silently = DeleteTopic). CI same via GitHub OIDC. |
| 21 | **External Secrets** | prod password git mein **kabhi nahi**; ESO Secrets Manager se pull→`shopflow-db` Secret (refreshInterval 1h rotation, IRSA auth). K8s Secret = base64 (RBAC+encryption-at-rest alag). Git = forever. |
| 22 | **Cognito prod** | managed IdP (Keycloak dev). Public client + **auth-code+PKCE** (no secret, implicit nahi). Access 15m (leak window) + refresh 30d (revocable). `cognito:groups`→ROLE_*. Config not code. |
| 23 | **MSK prod** | 3 brokers/3 AZ, **acks=all + RF3 + minISR2** → acked = 2 replicas → 1 broker/AZ loss pe 0 data loss. IAM auth (no password). `acks=1` = silent loss. 2 brokers down → write fail (intended). |
| 24 | **NetworkPolicies** | zero-trust: **default-deny-ingress** + per-service allow-list (order←ingress/mon, inv←order, pg←services). Lateral-movement contain. **CNI (Calico/Cilium) enforce kare** warna decorative — test karo. |
| 25 | ⭐ **Correctness model** (CAPSTONE) | **Strong** jahan ek node decide kar sakta (atomic stock `UPDATE`, order-state+outbox **1 txn**, `@Version`) · **eventual** jahan cross-service (Kafka **at-least-once + idempotency = effectively-once**, ordering-per-key + status-guards, progress-as-state + reconciler). CAP: saga>2PC (A chuno, C ko idempotency/compensation se kamao). Worst-case (relay crash + double-deliver + reorder) → invariants hold. |
| 26 | ⭐ **Scaling 100x** (CAPSTONE) | Pehla ceiling naam do (number se), **4/4 mein 3 "pods add" se hilte nahi**: DB conn (pods×`5`>`max_connections`→payment bhi fail, CPU idle→PgBouncer+replicas) · hot-row lock (ek SKU serialize→queue+Redis-gate, final Postgres) · per-pod limiter (`50`→cap=50×replicas drift→shared limiter) · relay (`BATCH 100`/1s **SKIP LOCKED**=scale-seam→CDC). Fix = **knob/component/redesign** kaunsa. |

## 2. Symptom → cause → fix (debugging, 2am wala)
| Symptom | Likely cause | Fix / check | Drill |
|---|---|---|---|
| CONFIRMED climbing, PAID flat | payment-service consumer **lag** (bad deploy/poison) | rollback consumer; `--describe --group payment-service` | D01 |
| 201 OK but no email/charge; `outbox_unpublished`↑ | Kafka down / relay stuck (publish leg) | fix Kafka → relay drains; "can't publish" vs "not trying" | D02 |
| p99 spike, 5xx normal | downstream **saturation** (DB pool 5/5, queue) | trace→slow span; scale/cap; retries hurt | D03 |
| Pod CrashLoop, exit **137** | **OOMKilled** (cgroup, non-heap too) | `describe`→Last State; bound the cache, not just raise limit | D04 |
| Outage but **SLO alert silent** | SLI metric (`le=1.0` bucket) absent | guard-test metric existence; alert on `absent()` | D05 |
| Sold 5 of 3 | non-atomic decrement / **cache in write path** | atomic `UPDATE…WHERE`; k6 `count==3` gate | D06 |
| Two emails (double?) | idempotency gap (dedupe after side-effect) | dedupe **in same txn**; PK=eventId proves no double charge | D07 |
| One partition lag, others fine | **poison pill** + DLT topic missing | create DLT / fix schema; per-partition lag read | D08 |
| Everyone **401** after IdP change | wrong `JWT_ISSUER_URI` (iss/JWKS) | fix issuer; 401=auth not role; smoke-test protected call | D09 |
| Redis down → DB on fire, no errors | **fail-open** cache → DB stampede (hit-rate 1/(1−h)) | restore Redis; single-flight + DB cap | D10 |
| inventory blip → order-service zero endpoints | readiness coupled to **aggregate/downstream** | scope readiness `readinessState,db` | D11 |
| DLT 9k records, lag 0, no alert | working DLT silently eating schema-drift | fix-then-**replay** (idempotent); alert on DLT depth | D12 |
| Deploy "in progress" 20m | **paused-by-design** vs auto-aborted vs stuck | read `argo rollouts get`; don't rollback a passing canary | D13 |
| 502s every deploy | removed **preStop** (SIGTERM vs dereg race) | restore preStop (stop-new) + graceful (finish-old) | D14 |
| Replicas flap on flat traffic | removed scaleDown **stabilization window** | restore `stabilizationWindowSeconds:300` | D15 |
| Scaled pods, payment-svc conn-errors, CPU idle | pods×`5` > shared **`max_connections`** (wrong-tier scale) | scale back; **PgBouncer**+replicas (pods≠conns) | D16 |

## 3. Quick-fire facts
- **Status codes** (`GlobalExceptionHandler`): 404 not-found/IDOR · 409 not-cancellable / dup-key / **@Version optimistic-lock** · 422 Idempotency-Key cross-user · 429 rate-limit · 503 inventory-unavailable(+orderId) · 400 validation(problem+json).
- **Numbers:** DB pool **5/pod** (k8s; default 10 local), `max_connections` 200 (RDS 300). Kafka **3 partitions** local / **6** MSK (RF3, min.insync 2). Mem **512Mi limit / 384Mi req** (heap 75%). Rate **50/s/pod**. Breaker **50%/10 calls/10s**. Retry **3×/200ms**. Cache TTL **60s**. Outbox retention **7d**. HPA **2–6 / CPU70% / scaleDown 300s**. k6 **300 buyers/3 units → confirmed==3**, p95<800, browse p99<300.
- **Golden rules:** at-least-once → idempotent consumer · money-moved → no auto-cancel · stock decision → never cache · readiness → own-deps-only · DB in readiness not liveness · no `:latest` (SHA) · no AWS keys (IRSA) · least-privilege (no wildcard) · corrupt backup worse than none · a metric an alert needs must exist.
- **Saga one-liner:** `PENDING → CONFIRMED → PAID → REFUND_PENDING → REFUNDED`; no-money states (PENDING/FAILED) auto-cancel, money states (CONFIRMED/REFUND_PENDING) only alert.

> 1 hafta pehle: sirf ye + [`interview-notes/01-project-walkthrough.md`](interview-notes/01-project-walkthrough.md) + [`interview-notes/README.md`](interview-notes/README.md) rapid-fire. Khud test: [`comprehension/`](comprehension/README.md) (passage→reason) + [`debugging/`](debugging/README.md) (symptom→cause).
</content>
