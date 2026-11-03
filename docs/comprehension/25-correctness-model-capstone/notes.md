# 25 · CAPSTONE — correctness model — concept notes

> Comprehension: [`comprehension.md`](comprehension.md). Synthesis of 01/02/03/04/05/11/23. **Agar ek topic yaad rakhna ho, ye.**

## Kya (one line)
ShopFlow mein koi distributed transaction nahi — phir bhi correct, kyunki wo **strongly consistent sirf wahan hai jahan gap unrecoverable** (stock atomic UPDATE, order-state+event ek local txn via outbox), aur **baaki sab eventual** (cross-service Kafka) jo **safe** hai: at-least-once+idempotent (effectively-once) + ordering+status-guards + progress-as-state+reconciler.

## Easy way (Hinglish analogy)
Bank socho. **Cash counter pe paisa ginna (stock decrement)** — ye *abhi, atomic, strong* hona chahiye, warna do log ek note le lein. **Par "receipt email bhejna, ledger update karna" (payment/notification)** — ye *thodi der mein* ho jaye to chalega, bas **galat na ho**: email do baar aa jaye to customer ek hi baar padhe (idempotent), purana receipt pehle aa jaye to system confuse na ho (status guard), aur agar bich mein counter band ho gaya to **register (order status)** se pata chal jaye kahan tak hua (reconciler). Mehenga "strong" sirf cash-ginn pe kharcho; baaki ko **safe-to-repeat** banao.

## Kyu (ye problem solve karta hai)
2PC (distributed transaction) strong deta par **blocking + available nahi + slow** (CAP: C over A; PACELC: latency bhi). Saga A+low-L chunta, eventual — par **correct** kyunki har invariant step-pe preserve hota. Bina is model ke: oversell, double-charge, lost event, leaked stock — koi bhi ho sakta.

## Kaise — correctness layering (4 layers)
```
 LAYER 1  STRONG, single-row, NOW  ───────────────────────────────────
   stock:  UPDATE product SET qty=qty-:q WHERE sku=? AND qty>=:q   (row-lock, linearizable)
   order:  @Version optimistic lock (concurrent cancel → one 409)
 LAYER 2  DURABLE HAND-OFF (coupling) ───────────────────────────────
   order row + outbox_event  ── ONE local txn ──  (transactional outbox: no dual-write)
 LAYER 3  SAFE EVENTUAL FAN-OUT ─────────────────────────────────────
   relay (at-least-once, ack-wait) → Kafka (key=orderRef, acks=all/RF3/minISR2)
   consumers: idempotent (processed_event / PK=eventId / by-state)  +  status guards (order-independent)
   ⇒ duplicate = no-op, reorder = no-op  ⇒ EFFECTIVELY-ONCE
 LAYER 4  PARTIAL-FAILURE RECOVERY ──────────────────────────────────
   status = saga progress log; reconciler: no-money limbo → auto-cancel; money-moved → ALERT (never guess)
```

## Strong vs eventual — kahan kya (decision)
```
 "gap exploit ho sakta AUR unrecoverable?"
        │ YES → STRONG (atomic single-row, smallest scope)   ── stock, order-state
        │ NO  → EVENTUAL, par SAFE (idempotent + guard + reconcile) ── payment, notification, PAID/REFUNDED
 Rule: expensive guarantee smallest scope pe (1 row); baaki ko safe-to-repeat banao.
```

## CAP / PACELC (interview gold)
```
 2PC (strong):   partition pe A gँwata; no-partition pe bhi L (blocking, coordinator stall)
 Saga (ShopFlow): A + low L chunta; consistency EVENTUAL par invariants har step preserve
 ⇒ "eventual = correct, not just convenient" — kyunki guards+idempotency+compensation
```

## Worst-case interleaving (duplicate + reorder, invariants hold)
```
 relay send OrderConfirmed → Kafka HAS it → relay CRASH before markPublished
   → next run RESEND (dup #2 on wire)
 payment consume #1: charge + payment row (PK=eventId)
 payment consume #2: existsById(eventId) → SKIP (no 2nd charge)       ← dup absorbed (idempotency)
 order sees PaymentCaptured (maybe reordered):
   onPaymentCaptured filter(isConfirmed) → CONFIRMED? mark PAID once
   redeliver PaymentCaptured → order PAID (not CONFIRMED) → no-op       ← reorder/dup absorbed (status guard)
 FINAL: 1 charge, 1 PAID — effectively-once. ✅
```

## Invariants (hamesha sach)
| Invariant | Guarantee | Tod do to |
|---|---|---|
| No oversell | atomic `UPDATE…WHERE qty>=:q` | race/oversell (05) |
| No double-charge | payment PK=eventId | dedupe-after-effect (D07) |
| No lost acked event | outbox(1txn)+ack-wait+acks=all/RF3/minISR2 | publish-outside-txn / acks=1 (25E, 23) |
| No leaked stock on fail-refund | money-first (release after PaymentRefunded) | release-first (02E) |
| No auto-cancel paid order | reconciler: no-money cancel, money→alert | auto-cancel CONFIRMED (04E) |
| Dup/reorder harmless | idempotent + status guards | no dedupe/guard (03,11) |

## Understanding check (khud se — agar ye aate, tu tagda)
1. Strong kahan, eventual kahan — aur **kyu** waise?
2. Exactly-once kyu nahi milta, effectively-once kaise?
3. Ordering-per-key **aur** status-guard dono kyu (ek kaafi kyu nahi)?
4. Saga vs 2PC — CAP/PACELC mein kya trade?
5. `outbox.publish` ko txn ke bahar nikaal do → kaunsa invariant tootta, kaunsa limbo?
6. "Expensive guarantee smallest scope pe" — ek line mein principle?

## Interview mein ye bolo (ek paragraph — ratta maaro)
*"ShopFlow mein koi distributed transaction nahi. Strongly consistent sirf wahan jahan gap unrecoverable — stock atomic `UPDATE…WHERE quantity>=:qty` se, aur order-state+uska-event ek local transaction (outbox) + @Version se. Baaki sab cross-service eventual over Kafka — safe kyunki teen guarantee: at-least-once + idempotent consumers (effectively-once), ordering-per-key + status-guards (reorder/duplicate no-op), aur progress order-status mein + reconciler (no-money limbo auto-cancel, money-moved limbo alert, kabhi guess nahi). Ye ek CAP choice hai — saga over 2PC, availability + low latency over blocking strong consistency — aur correct hai, convenient nahi, kyunki har invariant (no oversell, no double-charge, no lost event, no leaked stock, no cancelled-paid-order) har step pe preserve. Transactional outbox wo ek line of coupling hai jo strong write aur eventual fan-out ko diverge hone se rokti."*

> Capstone — ye sab ek saath: [01](../01-outbox-dual-write/)·[02](../02-saga-cancel-refund/)·[03](../03-idempotency-three-ways/)·[04](../04-order-limbo-reconciler/)·[05](../05-overselling-concurrency/)·[11](../11-kafka-dlt-lag/)·[23](../23-msk-kafka-prod/). HLD depth: [`../../HLD-SYSTEM-DESIGN.md`](../../HLD-SYSTEM-DESIGN.md).
</content>
