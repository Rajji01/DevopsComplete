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
| 27 | ⭐ **Observability SLI** (CAPSTONE) | SLO "99.5% 1s mein succeed/30d" = **avail+latency ek number**. `bad = 5xx + slow-2xx`(`le=1.0`), denom **2xx+5xx (4xx out)**. Alert **multi-window multi-burn** (1h **and** 5m > `14.4×0.005`=7.2% → page; long=real, short=abhi). Incident: **metric→trace→log** utro. Monitor-the-monitor (alert jis series pe depend karta wo exist kare). |
| 28 | **Concurrency control** | Lost-update ke 3 ilaj, teeno repo mein: **atomic UPDATE** (stock `where qty>=:qty`, no lock, rows-affected) · **optimistic `@Version`** (orders, Hibernate `WHERE version=?`, loser→409) · **pessimistic `FOR UPDATE SKIP LOCKED`** (relay, `@Lock(PESSIMISTIC_WRITE)`+timeout `-2`). Rare→optimistic (warna retry-storm), hot-row→pessimistic (warna convoy/deadlock), 1-col→atomic. Isolation READ-COMMITTED default (2-stmt RMW=lost update), write-skew→SERIALIZABLE SSI, big read→MVCC snapshot not `FOR UPDATE`. **Lock + remote call = pool-starve outage (D17)**; cross-service=saga not lock. |
| 29 | **Silent data bugs** | **No exception ≠ correct.** Full-row overwrite (no `@DynamicUpdate` → bina `@Version` partial-edits silently revert) · enum **ORDINAL bomb** (ShopFlow `STRING`=safe) · **index cliff** (un-indexed/implicit-cast → seq scan; dev 2ms/prod 4s → `EXPLAIN ANALYZE`) · `@Transactional` **self-invocation** (proxy bypass=no txn) + **`readOnly=true`** (no flush) · **stale read-your-writes** (replica lag, intermittent). Detect: primary pe re-read (lost-write vs stale-read), EXPLAIN, node-tagged traces. |
| 30 | **Replication / topology** | **Data boundary = failure mode.** DB-per-service (4 alag DB → no cross-service txn/JOIN → outbox/saga, eventual) · many-pods-1-DB (concurrency DB pe + `pods×pool≤max_connections`) · **sync standby** (Multi-AZ, HA/zero-loss, read ke liye nahi) vs **async replica** (read-scale, **lag→read-your-writes**) vs **CDC** vs **multi-primary** (conflict/split-brain). Payments sync, counter async. Idempotency=replay safe, conflict-resolution nahi. |
| 31 | **JPA at scale** | ORM chhupa cost: **OSIV** (`open-in-view` default true → conn held poore request; ShopFlow **false**) · **N+1** (lazy loop; flat entities→none; `join fetch`/DTO) · **OFFSET vs keyset** (OFFSET n rows scan+discard+`count(*)`; 50M→keyset seek; ShopFlow OFFSET=limitation) · unbounded **`findAll()`** (whale-customer OOM) · big-loop **flush/clear** (relay 100-bounded). **Generated SQL dekho** (query-count ∝ rows = N+1). |
| 32 | **Distributed locks** (real `shopflow-lock`) | 3 safety: **TTL** (crashed holder self-free) · **ownership token** (apna hi release, atomic Lua check-and-delete not GET-DEL) · **fence token** (monotonic; **resource** lower reject — **only** stall/GC-pause safety, Kleppmann). **Redlock** = N *independent* stores majority (`N/2+1`), minority release; safety disputed. Efficiency-lock (cache rebuild) Redlock-no-fence OK; **correctness** (double-pay) → fence **ya idempotency**. Money: **idempotency > lock** (lock-independent no-op). |
| 33 | **Distributed IDs** (real `shopflow-id`) | Coordination-free unique id; **id shape DB index affect karta** — random UUIDv4 PK inserts B-tree bhar scatter (page split/cache miss, scale pe dikhta), time-ordered = tail insert. **Snowflake** (64-bit 41ms+10node+12seq, 4096/ms/node, clock-backwards **refuse**, node-id chahiye) · **ULID** (128-bit base32, no node-id, same-ms suffix-increment) · **UUIDv7** (standard UUID, time-ordered, drop-in) · **Segment/Hi-Lo** (DB block `UPDATE..RETURNING`, dense, not-sortable, gaps). |
| 34 | **Rate limiting** (real `shopflow-ratelimit`) | 3 decisions: **algo** · **backend** · **limiter-down**. 5 algo: fixed (2x boundary burst) · sliding-log (exact/O(limit)) · sliding-counter (O(1)/approx/**default**) · token-bucket (**burst**, gateways) · leaky-bucket (**smooth**, fragile downstream). **Local=per-pod** (topic 26 drift, cap=perPod×replicas) vs **Redis+Lua=global** (shared counter+clock, atomic). Down → **fail-open** (allow) ya **fail-closed** (429); `ratelimit.backend.errors` alert. Global per-customer → token-bucket Redis. |
| 35 | **Bloom / Count-Min** (real `shopflow-sketch`) | **Error-direction = design.** **Bloom** "dekha kya?": any-bit-0=**definitely-not** (no false negative), all-1=probably → **fast negative gate** (absent→skip DB, cache-penetration topic 12; "present" trust mat karo → asli dedupe). `m=-n·ln p/(ln2)²`. **Counting Bloom** deletable (counters). **Count-Min** "kitni baar?": min-across-rows, **never underestimate** → heavy-hitter/abuse detect (abuser miss nahi, innocent over-count OK) → topic 34 throttle. Trust only safe answer. |

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
| Inventory says 5, warehouse has 2 (phantom stock) | **double release** under concurrency (read-guard, no `@Version`) | idempotent release / atomic guard | D16 |
| Scaled pods, payment-svc conn-errors, CPU idle | pods×`5` > shared **`max_connections`** (wrong-tier scale) | scale back; **PgBouncer**+replicas (pods≠conns) | D17 |
| Flash sale: 409 storm, p99 up, data still correct | **optimistic `@Version` on hot row** → conflict/retry storm | atomic `UPDATE…WHERE qty>=` (no lock, rows-0=cleansoldout) | D18 |
| "Placed order, list empty" — 1-3%, staging clean | **async replica lag** → read-your-writes miss (no error) | fresh reads → primary (pin/LSN-wait), not a sleep | D19 |
| 2ms staging, 4s prod, same SQL, no deploy | missing/defeated index → **Seq Scan** at volume | `EXPLAIN (ANALYZE)`; add index / fix implicit cast | D20 |
| Random `deadlock detected`, only under load | two-row writes in **opposite lock order** (A→B vs B→A) | lock in consistent order (min id first) + retry | D21 |
| Job ran twice despite a lock (double payout) | **GC pause > TTL** → 2 holders; resource ignored **fence token** | fence at resource / make op idempotent | D22 |

## 3. Quick-fire facts
- **Status codes** (`GlobalExceptionHandler`): 404 not-found/IDOR · 409 not-cancellable / dup-key / **@Version optimistic-lock** · 422 Idempotency-Key cross-user · 429 rate-limit · 503 inventory-unavailable(+orderId) · 400 validation(problem+json).
- **Numbers:** DB pool **5/pod** (k8s; default 10 local), `max_connections` 200 (RDS 300). Kafka **3 partitions** local / **6** MSK (RF3, min.insync 2). Mem **512Mi limit / 384Mi req** (heap 75%). Rate **50/s/pod**. Breaker **50%/10 calls/10s**. Retry **3×/200ms**. Cache TTL **60s**. Outbox retention **7d**. HPA **2–6 / CPU70% / scaleDown 300s**. k6 **300 buyers/3 units → confirmed==3**, p95<800, browse p99<300.
- **Golden rules:** at-least-once → idempotent consumer · money-moved → no auto-cancel · stock decision → never cache · readiness → own-deps-only · DB in readiness not liveness · no `:latest` (SHA) · no AWS keys (IRSA) · least-privilege (no wildcard) · corrupt backup worse than none · a metric an alert needs must exist.
- **Saga one-liner:** `PENDING → CONFIRMED → PAID → REFUND_PENDING → REFUNDED`; no-money states (PENDING/FAILED) auto-cancel, money states (CONFIRMED/REFUND_PENDING) only alert.

> 1 hafta pehle: sirf ye + [`interview-notes/01-project-walkthrough.md`](interview-notes/01-project-walkthrough.md) + [`interview-notes/README.md`](interview-notes/README.md) rapid-fire. Khud test: [`comprehension/`](comprehension/README.md) (passage→reason) + [`debugging/`](debugging/README.md) (symptom→cause).
</content>
