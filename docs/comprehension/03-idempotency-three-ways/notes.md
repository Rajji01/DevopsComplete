# 03 · Idempotency (three ways) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `OrderService.placeOrder`, `PaymentEventListener`, `OrderConfirmedListener`.

## Kya (one line)
Idempotent = ek operation do baar karne ka wahi asar jo ek baar ka. ShopFlow teen tarike se enforce karta hai — client `Idempotency-Key`, consumer `processed_event` dedupe, aur business row ka PK = eventId — plus refund "by state".

## Easy way (Hinglish analogy)
Lift ka button socho — ek baar dabao ya paanch baar, lift ek hi baar aati hai. Wahi idempotency. Ab button banane ke teen tarike:
- **Idempotency-Key** = tu apni **parchi number** leke aaya ("order #abc"); dukaandaar wahi number dobara dekhe to naya saman nahi deta, purana hi de deta — **par agar parchi kisi aur ki hai to tujhe uska saman nahi dega** (ownership).
- **processed_event table** = dukaandaar ek **register** rakhta hai "ye event-id handle ho gaya"; dobara wahi aaye to skip.
- **PK = eventId** = tu register alag nahi rakhta, **bill-book ka serial number hi event-id** bana deta — same number ka doosra bill ban hi nahi sakta (DB mana kar dega).
- **by state** = koi register nahi; bas dekho cheez ki **halat** — "already REFUNDED? to kuch mat karo."

## Kyu (ye problem solve karta hai)
Distributed system mein **retry/redelivery guaranteed hai**: client timeout pe dobara dabata hai, Kafka offset commit se pehle consumer restart ho to record dobara bhejta hai. Agar operation idempotent nahi, to double order / double charge / double email. Delivery ko exactly-once banana (DB/email ke saath) possible nahi — isliye **at-least-once maan lo aur consumer ko idempotent banao.**
Ek hi mechanism sab jagah fit nahi: client retry tab aata hai jab **event bana hi nahi** (to event-id wali table bekaar), payment row ke paas **already** natural id hai (alag table redundant), refund ke paas **koi nayi row hi nahi** (status hi guard).

## Kaise (step by step)
1. **Idempotency-Key** (`placeOrder`): header aaya → `findByIdempotencyKey`. Mila?
   - owner same → wahi order return (`created=false` → 200).
   - owner alag → `IdempotencyKeyConflictException` (422). Nahi mila → naya order.
2. **processed_event** (`PaymentEventListener.onPaymentEvent`, `@Transactional`): `existsById(eventId)`? → skip. Warna handle (switch), phir `processedEvents.save(eventId)` **usi transaction mein**.
3. **PK = eventId** (`OrderConfirmedListener.charge`): `payments.existsById(eventId)` → duplicate counter++ return. Warna charge + `new Payment(eventId, …)` save + outbox event — ek local txn. Race → PK constraint.
4. **by state** (`refund` / `onPaymentRefunded`): `findByOrderRef` → sirf `CAPTURED`/`REFUND_PENDING` ho to aage; warna no-op.

## Flow diagram (teen raaste)
```
 CLIENT RETRY                 KAFKA REDELIVERY                      KAFKA REDELIVERY            STATE GUARD
 POST /orders (Key)           payment event (eventId)              OrderConfirmed (eventId)    OrderRefundPending
     │                              │                                   │                          │
 findByIdempotencyKey          existsById(processed_event)?        existsById(payment PK)?     payment CAPTURED?
     │  found & owner?              │ yes → skip                        │ yes → skip              │ no (already REFUNDED)
     ├─ yes → return same (200)     │ no  → handle + save eventId       │ no  → charge+insert      │        → do nothing
     ├─ other owner → 422           │     (SAME transaction)            │   (PK blocks race)       └─ yes → refund once
     └─ none → create (201)
```

## Component diagram (kaunsa id, kahan se)
```
   client ─┐  Idempotency-Key (client-chosen)     ──► orders.idempotency_key (unique) + ownership
           │
 upstream ─┤  eventId (from event)  ──► order-svc processed_event (dedupe table, same-txn)
  event   ─┤  eventId (from event)  ──► payment.id  == PK  (natural key, PK constraint)
           └  (no id)  ──► refund: payment.status CAPTURED→REFUNDED  (state is the guard)
```

## Alternate approaches (kab kaunsa)
| Mechanism | Key kahan se | Extra state | Best jab |
|---|---|---|---|
| **Idempotency-Key** | client | unique column + ownership | HTTP edge, client retries, pre-event |
| **Dedupe table (`processed_event`)** | upstream event | ek table, same-txn | consumer ke paas apni koi natural id nahi |
| **Natural key = PK** | upstream event | koi nahi (row khud) | business row insert ho hi raha hai |
| **Idempotent by state** | row ki status | koi nahi | one-way transition on existing row |
| **Idempotent producer+txn (Kafka EOS)** | Kafka internal | — | sirf Kafka→Kafka, DB/email loop mein nahi |

## Understanding check
1. Teeno mechanisms mein id kaun chunta hai — client ya event?
2. Same key, alag customer → kya hota hai aur kyu?
3. PK constraint wo kaunsa case pakadta hai jo `existsById` miss karta?
4. Refund "by state" kab fail karega (kaunsa feature)?
5. Email ko fully idempotent kyu nahi bana sakte?

## Interview mein ye bolo
*"At-least-once given hai to idempotency zaroori. Main natural key use karta hoon: client Idempotency-Key (ownership check ke saath, warna cross-user replay bug), Kafka consumer `processed_event` dedupe same transaction mein side-effect ke saath, aur payment row ka primary key hi eventId — PK race ko bhi pakadta hai jo existsById miss karta. Refund by-state idempotent hai, koi table nahi. Ek hi mechanism sab jagah isliye nahi kyunki har context ki apni natural key alag hai."*

> Related: [`../01-outbox-dual-write/`](../01-outbox-dual-write/) (at-least-once ka source), [`../02-saga-cancel-refund/`](../02-saga-cancel-refund/) (by-state refund).
</content>
