# 25 · CAPSTONE — the correctness model: strong where it must be, eventual where it can be

> The flagship synthesis topic. If you can hold **this** in your head, you understand ShopFlow's whole distributed-correctness story. Companion notes: [`notes.md`](notes.md). Builds on comp 01/02/03/04/05/11/23. Grounded across `OrderService`, `OutboxRelay`, `OrderConfirmedListener`, `PaymentEventListener`, `ProductRepository`, `OrderReconciler`.

---

## Scenario (read cold)

> *A senior design review of ShopFlow's correctness. Read once — it's dense on purpose — then answer.*

ShopFlow spans four services, four databases, and a Kafka log, with **no distributed transaction** anywhere. Yet it maintains hard business invariants — never oversell, never double-charge, never lose an acknowledged event, never leak stock on a failed refund, never auto-cancel a paid order. The way it does this is by being **strongly consistent exactly where correctness demands it, and eventually consistent everywhere else** — and by making every eventual step **safe to repeat and safe to see out of order**.

**Strong consistency — single-row, single-DB, now.** Two things must be correct *the instant they happen*, so they use a **single atomic operation on one row**:
1. **Stock** — `UPDATE product SET quantity = quantity - :qty WHERE sku=? AND quantity >= :qty`. The row lock serialises concurrent buyers and the `WHERE` re-checks inside the same statement, so overselling is **impossible** — this is linearizable on that row.
2. **Order state transitions** — the `Order` aggregate carries a `@Version`; two concurrent cancels race and one gets a `409`, never a lost update. And the **order row + its outbox event commit in one local transaction** (the transactional outbox), so "order changed" and "event to tell everyone" are atomic — no dual-write hole.

**Eventual consistency — cross-service, soon.** Everything *between* services is **asynchronous over Kafka**, so it's eventually consistent: after an order is `CONFIRMED`, payment-service will *eventually* charge and order-service will *eventually* become `PAID` — usually in milliseconds, but the system is correct even if it takes longer (consumer lag, a pod restart). The customer's **reserve** answer is synchronous (they need it now, so order→inventory is orchestrated over REST); **payment and notification** are choreographed over events (nobody is waiting synchronously).

What makes eventual *safe* is three guarantees that compose:
- **At-least-once + idempotent consumers.** The relay is at-least-once (it may resend after a crash between `send` and `markPublished`), so every consumer dedupes — `processed_event` by `eventId`, `payment` PK = `eventId`, refund by state. A duplicate is a no-op. There is no exactly-once with a DB/email in the loop; **at-least-once + idempotency = effectively-once**.
- **Order per key, and out-of-order-safe anyway.** Events are keyed by `orderRef`, so one order's events stay ordered within a partition. But the handlers *also* don't trust ordering: `onPaymentCaptured` only acts on a `CONFIRMED` order, `onPaymentRefunded` only on `REFUND_PENDING` — **status guards** make a stale or reordered event a no-op, not a corruption.
- **Progress is recorded, so partial failure is recoverable.** `placeOrder` is deliberately **not** one big transaction across the HTTP call (that would exhaust the pool); each step is saved, and the **order status is the saga's progress log**. A crash mid-saga leaves a definite state, and the `OrderReconciler` settles the "outcome unknown" ones — but only where **no money has moved** (FAILED/PENDING auto-cancel via idempotent release); where money *might* have moved (CONFIRMED/REFUND_PENDING) it **refuses to guess** and alerts a human.

So the correctness model is a layering: **atomic single-row writes** for the invariants that can't tolerate a gap, **durable hand-off** (outbox) so the atomic write and its announcement can't diverge, **at-least-once + idempotency + status guards** so the async fan-out is safe to repeat and reorder, and a **reconciler + alert** so partial failures resolve or surface rather than rot. No 2PC, and no lost correctness.

---

## Questions

### A — Literal
1. Name the two things that are **strongly** consistent (atomic single-row), and the mechanism for each.
2. Which parts are **eventually** consistent, and why is that acceptable for them?
3. What three composing guarantees make eventual consistency *safe*?

### B — Inference
4. "At-least-once + idempotency = effectively-once." Why can't ShopFlow get true exactly-once, and why is effectively-once just as correct for the business?
5. Events are ordered per key, **yet** the handlers also guard on status. Why keep both — isn't per-partition ordering enough on its own?

### C — Trade-off (architect)
6. ShopFlow chose **saga (eventual) over 2PC (strong)** for the cross-service flow. Frame this as a CAP/PACELC trade-off: what did it give up, what did it gain, and why is eventual *correct* (not just convenient) here?
7. Overselling is prevented with a **strongly-consistent single-row UPDATE**, but the order→payment flow is **eventual**. Why is it right to spend "strong consistency" on stock but not on the whole saga — what principle decides where to put the expensive guarantee?

### D — Transfer / extension (hard)
8. A reviewer says "just make `placeOrder` one big `@Transactional` across the inventory HTTP call and the DB — then it's all strongly consistent, no saga needed." Give the **two** distinct reasons this is wrong (one performance, one correctness-of-distributed-state), and what it would break under load and under partial failure.
9. Walk a **worst-case interleaving** and show the invariants hold: the relay publishes `OrderConfirmed`, crashes before `markPublished`; payment-service charges twice (redelivery) *and* order-service's `PaymentCaptured` arrives **before** it has processed its own confirm. Trace it — where do the duplicates and the reorder get absorbed, and what's the final state?

### E — Spot-the-flaw (senior)
10. To "reduce latency", a dev moves the `outbox.publish(...)` **out** of the `saveWithEvent` transaction — the order commits first, then the event is published in a separate step right after. Under a crash **between** those two steps, which invariant breaks, which failure mode from comp 04 appears, and why does this single change collapse the whole "strong write + durable hand-off" layering?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **(i) Stock** — one atomic conditional `UPDATE … WHERE quantity >= :qty` (row lock + in-statement re-check), so it's linearizable on that row. **(ii) Order state** — the `Order` aggregate's `@Version` optimistic lock (concurrent cancels → one 409, no lost update), and the **order row + outbox event in one local transaction** (transactional outbox) so the change and its announcement are atomic.
2. Everything **between services** — payment, the PAID/CANCELLED/REFUNDED transitions driven by events, notification. It's acceptable because **nobody is waiting synchronously** for them (the customer already got their reserve answer), so a few milliseconds — or, under lag, longer — of "not yet charged" is fine as long as it's **correct when it converges**, which the guards and idempotency ensure.
3. **(a) at-least-once delivery + idempotent consumers** (dedupe on eventId / PK / state), **(b) ordering per key + status guards** (so stale/reordered events are no-ops), **(c) progress recorded in the order status + a reconciler** (so partial failure resolves or alerts).
</details>

<details>
<summary>B — Inference</summary>

4. True exactly-once requires that "consume the event, do the side effect, and commit the offset" be **one atomic transaction** — impossible when the side effect is a **DB write in a different system** or an **email** (you can't enroll Kafka's offset, Postgres, and an SMTP send in one transaction; that's the dual-write/2PC problem again). So delivery is at-least-once (Kafka will redeliver on a rebalance/crash). It's **just as correct** because the consumers are **idempotent**: processing the same `eventId` twice has the **same effect as once** (a dedupe skip or a PK conflict), so the *observable* outcome is exactly-once even though *delivery* isn't. Effectively-once = at-least-once delivery + idempotent effect.
5. Because they defend against **different failures**. Per-partition ordering guarantees order *within one partition for one key* — but it does **not** survive a **partition-count change** (re-hash), a **producer that emits out of order**, a **redelivery** that replays an old event, or a **bug**. The **status guards** (`filter(Order::isConfirmed)` etc.) make the handler correct **regardless of order**: a `PaymentCaptured` for an order that isn't `CONFIRMED` is simply ignored. Ordering is an optimization that keeps the common path clean; the status guard is the **correctness backstop** that holds even when ordering doesn't. Belt and suspenders — and the suspenders are the ones that actually guarantee correctness.
</details>

<details>
<summary>C — Trade-off (architect)</summary>

6. **CAP/PACELC:** a cross-service distributed transaction (2PC) would give **strong consistency (C)** but sacrifices **availability (A)** during a partition and, even with **no** partition (the PACELC "else"), pays **latency (L)** — every participant blocks holding locks until the coordinator decides, and a coordinator or participant failure can **stall the whole transaction** (2PC's blocking problem). The **saga** chooses **A + low L**: each local transaction commits independently and immediately, and the system reaches a consistent state **eventually** via events + compensations. It's **correct, not just convenient**, because the business invariants are preserved at every step (atomic single-row writes + idempotency + compensation), and the only thing "relaxed" is the *timing* of cross-service convergence — which no user is synchronously waiting on. You trade a window of "not yet consistent" for availability and latency, and you close that window safely.
7. Because the **cost of a gap differs**. For **stock**, any non-atomic window **immediately** allows a wrong outcome (oversell) that is **unrecoverable** without customer harm — so you pay for linearizability on that one row where it's **cheap** (single row, single DB, one statement). For the **saga**, a brief "not yet PAID" window is **harmless and self-correcting** (the event will arrive; the guards make it safe), and making it strongly consistent would cost a **distributed transaction** — expensive and fragile — for no correctness gain. Principle: **spend strong consistency only where a gap is both possible-to-exploit and unrecoverable; everywhere else, make the gap safe (idempotent + guarded + reconciled) and let it be eventual.** Put the expensive guarantee at the smallest scope that needs it (one row), not across the whole system.
</details>

<details>
<summary>D — Transfer / extension (hard)</summary>

8. **(i) Performance:** a single `@Transactional` spanning the **inventory HTTP call** holds a DB transaction **and its pooled connection** open for the whole network round-trip; under load every in-flight order parks a connection, the HikariCP pool (5/pod) drains, and the service stalls — a self-inflicted outage. **(ii) Correctness of distributed state:** a local DB transaction **cannot** make a **remote service's** side effect atomic with your commit — if inventory reserved the stock but your transaction then rolls back (or vice versa), you have an **inconsistent cross-service state** that a single DB transaction can't fix; you'd need 2PC (which inventory doesn't speak). Under **load** it exhausts the pool; under **partial failure** (inventory slow/down mid-call) it either blocks a connection until timeout or leaves stock reserved with no order (or an order with unknown reservation). That's exactly why ShopFlow **saves each step separately and records progress in the status**, with the reconciler settling the unknowns — the saga exists *because* you can't make cross-service state atomic.
9. Trace: the relay sends `OrderConfirmed`, Kafka **has it**, the relay crashes before `markPublished` → the outbox row stays unpublished → **next run resends** (duplicate #2 on the wire). payment-service consumes `OrderConfirmed` twice: the **first** charges and writes the `payment` row keyed by `eventId`; the **second** hits `existsById(eventId)` (or the PK) → **no second charge** (duplicate absorbed by idempotency). payment-service emits `PaymentCaptured`. Now suppose order-service sees `PaymentCaptured` **before** its own order is `CONFIRMED` is irrelevant here (the order *is* CONFIRMED by the time the event exists), but take the general reorder: `onPaymentCaptured` does `filter(Order::isConfirmed)` — if the order weren't CONFIRMED yet it would **do nothing** and a later redelivery (at-least-once) would complete it; since it is, it marks `PAID` once, and a **redelivered** `PaymentCaptured` finds the order already `PAID` (not `CONFIRMED`) → **no-op** (dedup also via `processed_event`). **Final state:** one charge, one `PAID`, exactly-once *observable* outcome — every duplicate absorbed by a PK/dedupe, every reorder absorbed by a status guard. Invariants hold.
</details>

<details>
<summary>E — Spot-the-flaw (senior)</summary>

10. Moving `outbox.publish` out of the `saveWithEvent` transaction **reintroduces the dual-write problem** — the order and its event are now **two separate writes to two systems with no atomicity**. Under a crash **between** them: the order is committed as `CONFIRMED` (or `PAID`, etc.) but the **event is never written** → it will **never be published** (there's no outbox row for the relay to find) → payment-service never charges / notification never emails. The order is **silently stuck** — which is exactly comp 04's **limbo**, except now it's a limbo the reconciler **can't** cleanly detect as "event lost" (the order looks validly CONFIRMED). The whole layering collapses because the **"durable hand-off"** layer's entire job was to make "the state changed" and "the event announcing it" **atomic**; take them apart and the strong single-row write is still fine, but it's no longer **coupled** to its announcement, so downstream can diverge from the source of truth. The transactional outbox is precisely the one line of coupling that makes "strong write + eventual fan-out" safe; removing it to "save latency" trades the system's core correctness for microseconds — the classic bad trade this whole model exists to avoid.
</details>

---

## Correctness invariants (ye hamesha sach)
| Invariant | Kaise guaranteed | Agar tod do |
|---|---|---|
| Never oversell | atomic `UPDATE…WHERE quantity>=:qty` (strong, 1 row) | read-check-write race (comp 05) |
| Never double-charge | payment **PK = eventId** (DB constraint) | dedupe-after-side-effect (D07) |
| Never lose an acked event | outbox (1 txn) + relay ack-wait + MSK acks=all/RF3/minISR2 | `acks=1` / publish outside txn (D-E here, comp 23) |
| Never leak stock on failed refund | money-first: release **only after** PaymentRefunded | release-before-refund (comp 02 E) |
| Never auto-cancel a paid order | reconciler only cancels no-money states; CONFIRMED→alert | auto-cancel CONFIRMED (comp 04 E) |
| Duplicate/reordered event harmless | idempotent consumers + **status guards** | no dedupe / no guard (comp 03, 11) |

## Tod ke dekho — Local (the invariants, together)
```bash
make up && TOKEN=$(make -s token)
make loadtest TOKEN=$TOKEN                 # 300/3 → confirmed==3 (no oversell), 0 5xx
# redelivery is a no-op (effectively-once): replay an event, payment row stays 1 (comp 03/D07 blocks)
# crash-window safety: stop kafka → 201 still, outbox holds it, restart → drains (comp 01)
# partial failure: inventory down → FAILED → reconciler → CANCELLED (comp 04)
# strong vs eventual: stock is instant & exact; PAID lags the charge by the consume (watch it converge)
docker compose -f shopflow/docker-compose.yml exec postgres psql -U inventory -d inventory -c \
  "select quantity from product where sku='PS5-SLIM';"   # 0, never -1 (strong, now)
```

---

## Interview concepts is passage se (architect-level)
- **Consistency boundaries** — strong (single-row atomic: stock, order-state+outbox) vs eventual (cross-service via events).
- **CAP / PACELC** — saga chooses A + low L over 2PC's C+blocking; eventual is *correct*, not just convenient.
- **Effectively-once** = at-least-once delivery + idempotent effect (true exactly-once impossible with DB/email).
- **Ordering vs guards** — per-key order is optimization; **status guards** are the correctness backstop (order-independent).
- **Progress-as-state + reconciler** — partial failure resolves (no-money) or alerts (money-moved); never guess.
- **Transactional outbox = the coupling** that makes "strong write + eventual fan-out" safe; remove it → dual-write limbo.
- **Put the expensive guarantee at the smallest scope** that needs it (one row), make everything else safe-to-repeat.

> **The one paragraph (interview):** *"ShopFlow has no distributed transaction. It's strongly consistent exactly where a gap is unrecoverable — stock via an atomic `UPDATE…WHERE quantity>=:qty`, and order-state+its-event via a single local transaction (outbox) with @Version. Everything cross-service is eventual over Kafka, which is safe because of three composing guarantees: at-least-once delivery + idempotent consumers (effectively-once), ordering-per-key plus status guards so reorders/duplicates are no-ops, and progress recorded in the order status + a reconciler that settles no-money limbo and alerts on money-moved limbo. That's a CAP choice — saga over 2PC, availability + low latency over blocking strong consistency — and it's correct, not just convenient, because every invariant (no oversell, no double-charge, no lost event, no leaked stock, no cancelled-paid-order) is preserved at each step. The transactional outbox is the single line of coupling that keeps the strong write and the eventual fan-out from diverging."*

> Related (ye sab yahan milte): [01](../01-outbox-dual-write/) · [02](../02-saga-cancel-refund/) · [03](../03-idempotency-three-ways/) · [04](../04-order-limbo-reconciler/) · [05](../05-overselling-concurrency/) · [11](../11-kafka-dlt-lag/) · [23](../23-msk-kafka-prod/). Debug the failures: [D01](../../debugging/D01-orders-stuck-confirmed.md)/[D02](../../debugging/D02-201-but-silent-downstream.md)/[D07](../../debugging/D07-duplicate-emails-charges.md).
</content>
