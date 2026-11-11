# Fintech / Payments / High-Transaction — tracker (OSTTRA defense)

> **Kya hai ye:** ShopFlow ko payments/post-trade angle se banane ka authoritative map. Har roadmap item → ShopFlow mein kahan (ya naya) → status → priority → **OSTTRA defense line** (interview mein bolne wala ek vaakya). Source of truth yahi file; STATE.md se link.
>
> **Edge (yaad rakh):** generic backend engineer se tera farq = tu asli paisa/trade handle karta hai. Har pattern ko OSTTRA trade lifecycle pe map kar: **capture → confirmation → clearing → settlement (T+1/T+2) → reconciliation → reg reporting**. Pattern + jahan use kiya + uske bina kya tootega = defensible.

## Build order (skeleton-first, step by step)

| # | Increment | Status |
|---|---|---|
| 0 | **`ledger-service` skeleton** — double-entry domain (Money, Account, JournalEntry, Posting), state machine, Flyway schema, pure invariant tested; persistence/API stubbed | **DONE (Walk 96)** — compiles + H2 context test green |
| 1a | **Ledger persistence** — `record()` writes entry + postings in one tx; idempotent by unique key (Walk-45 race recovery); accounts must pre-exist (`AccountService`, typo'd code = rejected posting); derived balance (signed sum). 5 persistence tests | **DONE (Walk 97)** |
| 1b | **REST layer** — `POST /accounts`, `POST /journal-entries` (idempotent), `GET /accounts/{code}/balance`, `GET /journal-entries?reference=` + RFC-7807 handler (unbalanced/unknown-account → 422, bad → 400) + 5 MockMvc tests | **DONE (Walk 98)** |
| 2 | Wire ledger to the saga: payment-service `PaymentCaptured`/`PaymentRefunded` → ledger posts double-entry (inbound Kafka consumer, inbox dedup) | **DONE (Walk 104)** — `LedgerPostingListener` on `payments.events`; DR CASH / CR SALES_REVENUE (refund = reverse); `Money.fromMajor` HALF_EVEN at the boundary; **inbox = the journal entry's own unique `idempotency_key` (= eventId), no separate table** — avoids the Walk-99 `@Transactional` trap; 4 listener tests + 2 rounding tests |
| 3 | **Reconciliation** job: internal ledger vs external statement (PSP/bank CSV) → matched / unmatched / exception buckets + metric + alert + runbook | **DONE (Walk 105)** — `ReconciliationService` (ledger net cash per ref vs `SettlementStatementSource`), 3 buckets (MISSING_IN_LEDGER / MISSING_IN_STATEMENT / AMOUNT_MISMATCH), gauges `ledger_recon_{matched,unmatched,mismatch}`, `@Scheduled` daily (skips empty feed), `LedgerReconExceptions` alert + promtool test + runbook; 5 tests. ACL seam via `SettlementStatementSource` (empty default) |
| 4 | Payment **state machine** hardening: explicit INITIATED→AUTHORIZED→CAPTURED→SETTLED→RECONCILED (+FAILED/REVERSED), illegal transitions blocked, auth≠capture≠settlement | TODO (enum done in skeleton) |
| 5 | k8s/helm/monitoring/networkpolicy for ledger-service + CI image matrix + postgres-smoke test | TODO |

## Roadmap → ShopFlow map (status: ✅ hai · 🟡 partial · 🆕 naya)

### 1. HLD / Payments core
| Item | ShopFlow | Status | OSTTRA defense line |
|---|---|---|---|
| **Idempotency** (key + payload-hash bind, dedup table) | `OrderService.placeOrder` (Idempotency-Key + unique constraint race, Walk 45); consumer `processed_event` inbox | 🟡 key hai, payload-hash binding nahi | "Trade capture pe idempotency key lagaya kyunki upstream retry karta tha; same key + alag payload = reject (replay guard)." |
| **Double-entry ledger** | 🆕 `ledger-service` | ✅ saga-wired (Walk 104) | "Har money movement = balanced debit+credit, immutable append-only; balance derived, stored nahi — netting/position ka base yahi hai. PaymentCaptured → DR CASH / CR SALES_REVENUE, refund = reverse." |
| **Exactly-once illusion** | outbox (at-least-once) + `processed_event` idempotent consumer = effectively-once | ✅ | "Exactly-once network pe exist nahi karta; at-least-once delivery + idempotent consumer = effectively-once." |
| **Saga (compensating txn)** | choreography: order↔inventory↔payment; cancel/charge race → REFUND_PENDING compensation (Walk 43) | ✅ | "Distributed txn bina 2PC; choreography events se, aur compensating transaction (refund/reversal) se failure undo." |
| **Transactional Outbox + Inbox** | `shopflow-outbox` (FOR UPDATE SKIP LOCKED relay) + `processed_event` inbox | ✅ | "Dual-write problem: DB commit + event publish ek local txn mein (outbox row), relay baad mein publish — confirmation events reliably jaate the." |
| **Payment/trade state machine** | `OrderStatus` (PENDING→CONFIRMED→PAID→REFUND_PENDING→REFUNDED + FAILED/CANCELLED/REJECTED); 🆕 `JournalEntryStatus` | 🟡 order-level hai, payment auth/capture/settle alag nahi | "Auth ≠ capture ≠ settlement teen alag states; illegal transition block karta hai state machine." |
| **Reconciliation** | ✅ `ReconciliationService` (Walk 105) — ledger net cash/ref vs settlement statement, 3 exception buckets + alert/runbook; `OrdersStuckConfirmed` reaper limbo alag | ✅ | "Internal ledger vs bank/PSP statement — matched/unmatched/exception buckets; post-trade ki roti-roti. Mismatch = unexplained, not lost — same-day control." |
| **Consistency boundaries** | strong: ledger/balance write (optimistic lock); eventual: reporting/dashboards | 🟡 | "Balance/ledger pe strong consistency; reporting pe eventual chalega — CAP/PACELC payments context mein." |
| **Money handling** | 🆕 `Money` (minor units/paise, ISO-4217 currency, no float) | 🆕 skeleton | "Kabhi float/double nahi — minor units (paise) long mein, currency+scale, banker's rounding." |
| **Sharding / hot partition** | Kafka key = `orderRef` (per-aggregate); hot-counterparty shard risk documented | 🟡 design-only | "High TPS pe partition by aggregate; ek bada counterparty pura shard na dube — hot-partition guard." |

### 2. Microservices patterns (payments-flavored)
| Item | ShopFlow | Status |
|---|---|---|
| Idempotent consumer (Kafka) | `OrderEventListener`/`PaymentEventListener` + `processed_event` | ✅ |
| Resilience4j (CB, timeout, retry+jitter, rate limiter, bulkhead) | order-service `InventoryClient` + config (bulkhead 🟡 nahi) | 🟡 |
| DLQ + poison message | `KafkaErrorConfig` DLT + `KafkaMessageDeadLettered` alert (Walk 40) | ✅ |
| Anti-Corruption Layer (external PSP seam) | 🆕 (PSP adapter boundary in ledger recon) | 🆕 |
| CQRS + Event Sourcing (ledger/audit) | 🆕 (ledger postings = natural event log) | 🆕 |
| Correlation/trace ID propagation | Micrometer tracing + OTLP, Kafka header propagation | ✅ |
| Contract testing (Pact) | 🆕 (event-contract copies verified by hand, Walk 82) | 🆕 |
| Scatter-gather (fraud+risk+limit parallel) | 🆕 | 🆕 |

### 3. DevOps / Reliability
| Item | ShopFlow | Status |
|---|---|---|
| Observability (RED, golden signals) | Prometheus + Grafana RED dashboard + recording/SLO rules | ✅ |
| SLO/SLI/SLA + error budget | `slo-rules.yml` multi-window burn-rate | ✅ |
| Deploy (blue-green/canary/flags) | Argo Rollouts canary + blue-green; expand-contract Flyway (V3) | ✅ |
| Kafka ops (lag, rebalance, sizing, backpressure) | `KafkaConsumerLagHigh`; partitions 3/6; backpressure 🟡 | 🟡 |
| DR / RPO-RTO, multi-region | backup cronjob + `PostgresBackupMissed` (Walk 92); RDS PITR; multi-region 🆕 | 🟡 |
| Incident mgmt (runbooks, postmortem) | `docs/runbooks/*` + postmortem template | ✅ |
| Secrets / PCI (tokenization, vault, no PAN in logs) | External Secrets + IRSA; tokenization 🆕; no-PAN-in-logs 🆕 | 🟡 |

### 4. Hard-problem ammo (map to existing finds)
- Retry-without-idempotency → duplicate charge → **Walk 45** (idempotency race) + **Walk 71** (gateway idempotency key).
- Race on balance → double-spend → **optimistic locking** (`@Version`), **Walk 55** (double-release phantom stock = same class).
- PSP timeout, ambiguous state → **reconciler / OrdersStuckConfirmed** (Walk 53); increment 3 recon.
- Orphaned pending txns → **reaper/sweeper** (`reconcileFailedOrders`).
- Webhook twice / out-of-order → idempotent consumer + **outbox ordering limit** (Walk 89).
- Recon mismatch → exception queue (increment 3).
- Retry storm / stampede → retry jitter (Walk 39) + cache `sync=true` (Walk 54) + `ProductCacheSilentlyDead` (Walk 91).
- Clock skew / DST in settlement cutoff → 🆕 (T+1 cutoff handling, increment 4).
- Rounding drift over millions → **`Money` minor-units + banker's rounding** (skeleton).
- Migration lock at peak → expand-contract Flyway (V3 order-service).

### 5. Reference names (weight-adders)
Stripe (idempotency keys + ledger), Uber (Temporal workflows), Airbnb (double-entry ledger post), PhonePe/Razorpay (UPI recon at volume). **Tera domain:** FIX/SWIFT messaging, netting, clearing, EMIR/MiFID reg reporting — ye bolte hi generic candidates se alag.

---
**Rule:** har increment CI-green + STATE/SESSION-LOG/notes synced (CLAUDE.md). Skeleton ke stubs `UnsupportedOperationException` + `TODO(increment N)` se marked hain — koi fake "done" nahi.
