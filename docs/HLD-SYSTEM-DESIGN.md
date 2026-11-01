# HLD / System Design — ShopFlow (interview-ready, grounded)

> System-design interview ke liye: **apne hi system** ko HLD lens se samjho — kaise scale karta, kahan bottleneck, kaunse consistency trade-off, failure pe kya. Sab **real ShopFlow numbers** se. Architecture diagram: [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md#1-big-picture). LLD: [`LLD-DESIGN-PATTERNS.md`](LLD-DESIGN-PATTERNS.md).

## 1. Ek line + requirements
**Kya:** e-commerce checkout — order lo, stock reserve karo, pay karo, notify karo; oversell kabhi nahi, deploy rollback-able, failures observable.

| Non-functional | Kaise mila |
|---|---|
| **No oversell** (correctness) | atomic conditional UPDATE (DB row-lock) |
| **Exactly-once *effect*** | transactional outbox (at-least-once) + idempotent consumers |
| **Available under dependency failure** | sync reserve pe circuit breaker + reconciler; async pe Kafka buffer |
| **Rollback-able releases** | SHA-pinned images, Argo Rollouts canary, no `:latest` (Kyverno) |
| **Observable** | RED metrics, SLO burn-rate alerts, traces↔logs, runbooks |

## 2. Scaling story (kaise 1x → bada hota)
- **Services stateless** → horizontal scale. HPA CPU 70%, min 2 max ~6-10. Koi in-memory session nahi (JWT stateless).
- **Kafka partitions = consumer parallelism.** `KAFKA_NUM_PARTITIONS=3` → ek consumer group mein 3 pods tak parallel; partition-key = `orderRef`/`aggregateId` → ek order ke events **ordered** (same partition), par alag orders parallel.
- **DB connection budget (asli math):** pool **5/pod** × HPA max 10 × 2 heavy services = ~100; `max_connections=200` (RDS 300). Isliye pool chhota rakha — warna autoscaling peak pe DB gira deta (Walk 2 ka asli finding).
- **Read-heavy products → Redis cache-aside** (inventory), har stock-change pe evict; cache down = fail-open to DB (never a hard dependency).

## 3. Consistency / CAP choices
- **Sync reserve** (`order → inventory` REST): customer jawab ka intezaar kar raha → **strong-ish, low-latency** chahiye. Isliye sync + circuit breaker (CP-leaning on that path: inventory down → fail fast, FAILED + reconciler).
- **Async payment + notification** (Kafka): customer ko turant chahiye nahi → **eventual consistency** (saga). Order CONFIRMED turant, PAID thodi der mein.
- **Saga, not 2PC:** distributed transaction (2PC) blocking + fragile; saga = local commits + compensation → available, partition-tolerant.
- **Line to say:** "Jahan user wait kar raha wahan sync+strong; jahan background wahan async+eventual — ek hi system mein dono, consciously."

## 4. Bottlenecks + mitigations (yahi interview poochta)
| Bottleneck | Kyu | Mitigation (hai / aage) |
|---|---|---|
| **Hot SKU write contention** | atomic decrement ek SKU ki row ko serialise karta → ek super-hot product pe writes queue | hai: correct-but-serialised. Aage: shard stock / reservation queue / in-memory token bucket per SKU |
| **DB connections** | pool × pods × services DB limit cross kar sakta | hai: pool 5 + max_connections 200/300 (budgeted) |
| **Kafka consumer lag** | producers > consumers | hai: partitions=3 + HPA + `KafkaConsumerLagHigh` alert; aage: zyada partitions |
| **Rate limiter per-pod** | resilience4j per-pod → N pods = N×limit | **open:** shared Redis/gateway limiter (STATE §4) |
| **Outbox relay single-tx across Kafka I/O** | lamba broker hold pool pressure | **open (low):** broker I/O tx ke bahar |

## 5. Failure domains — "X mar gaya to?"
- **inventory-service down:** breaker opens → order `FAILED` + 503; `OrderReconciler` grace ke baad release+CANCELLED (release idempotent = no-op agar reserve hua hi nahi). `InventoryCircuitOpen` alert.
- **Kafka down:** outbox rows unpublished pade rehte (lost nahi); `outbox_unpublished` gauge → `OutboxBacklogGrowing` alert; wapas aane pe relay flush.
- **DB down:** readiness fail (group includes `db`) → pod LB se hat-ta (par inventory-dependency readiness mein nahi — warna ek outage do ban jaaye).
- **pod crash mid-saga:** PENDING too long → reconciler; event half-processed → idempotent consumer + outbox at-least-once; CONFIRMED/REFUND_PENDING stuck → `OrdersStuckConfirmed` alert (human, no auto-cancel — paisa move ho sakta).
- **refund declined:** `PaymentRefundFailed`, order REFUND_PENDING, human (Walk 20) — kabhi fake REFUNDED nahi.

## 6. Capacity (back-of-envelope — flash sale)
- Scenario: 3 PS5, 300 buyers ek saath. Expected: **exactly 3 CONFIRMED**, 297 REJECTED, 0 oversold, 0 5xx. (k6 `flash-sale.js` isi ko threshold banata: `orders_confirmed == 3`.)
- Rate limiter 50/s/pod → burst protection HPA react karne se pehle; excess 429 (SLO count nahi hota — Walk 14).
- order p95 < 800ms, browse p99 < 300ms (k6 thresholds).

## 7. "100x pe kya badloge" (growth)
- **Shared rate-limit** (Redis/gateway) — per-pod ab bottleneck.
- **Schema Registry** (Avro/Protobuf) event contracts ke liye — abhi "ignore unknown fields".
- **Kafka partitions ↑** + consumer pods ↑; keep key=aggregateId for ordering.
- **DB:** read replicas (read-heavy), partition orders by time/customer; outbox cleanup aggressive.
- **Hot-SKU:** reservation queue ya per-SKU sharding.
- **Multi-region:** MSK/RDS cross-region, idempotency keys already there.

## 8. Design-a-feature scenarios (practice)
1. **"Order history page, 50M orders."** → index `(customer_id, created_at desc)` (already hai!); **keyset pagination** (not OFFSET) deep pages ke liye; cache hot customers; read-replica. (INDEXING-HIBERNATE §5.)
2. **"Product search."** → alag read-model (Elasticsearch/OpenSearch), inventory events se populate (CDC/outbox); search service products ka owner nahi — eventual read-model. CAP: search eventual, checkout strong.
3. **"Flash-sale 1M users, 100 units."** → waiting-room/queue (token), shared rate-limit, stock in Redis with atomic decr + reconcile to DB, HPA pre-scale, circuit breakers everywhere.

## 9. HLD interview checklist (ShopFlow answers)
- **Scale?** stateless+HPA, Kafka partitions, pool budget, cache.
- **Consistency?** sync-strong reserve, async-eventual payment; saga not 2PC.
- **Bottleneck?** hot-SKU row, DB connections, per-pod rate-limit.
- **Failure?** breaker+reconciler, outbox buffer, readiness, stuck-alert.
- **No oversell?** atomic conditional UPDATE.
- **Exactly once?** outbox + idempotent receiver (at-least-once + dedupe).
- **Deploy safely?** SHA pin, canary (Argo Rollouts), Kyverno no-:latest, rollback.
- **Observe?** RED + SLO burn-rate + traces↔logs + runbooks.

---
### Related
Diagrams [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md) · LLD [`LLD-DESIGN-PATTERNS.md`](LLD-DESIGN-PATTERNS.md) · DB [`INDEXING-HIBERNATE.md`](INDEXING-HIBERNATE.md) · AWS prod [`interview-notes/09-aws-production.md`](interview-notes/09-aws-production.md) · bug stories [`SELF-WALK.md`](SELF-WALK.md) · interactive [`../labs/comprehension.html`](../labs/comprehension.html)
