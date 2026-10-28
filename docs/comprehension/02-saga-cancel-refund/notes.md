# 02 · Saga, choreography & the refund compensation — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `OrderService.cancel/onPaymentFailed/onPaymentRefunded`, `OrderConfirmedListener.charge/refund`.

## Kya (one line)
Ek business flow jo kai services ke local transactions se banta hai; distributed transaction ki jagah har step ki ek **compensation** hoti hai jo use undo karti hai. ShopFlow: reserve → confirm → pay → (fail pe) release+cancel; PAID cancel → refund saga.

## Easy way (Hinglish analogy)
Shaadi ki planning socho — koi ek boss sab kuch control nahi karta. Caterer dekhta hai "date fix hui" (event) to khana plan karta hai; decorator wahi event dekh ke saja deta hai. **Choreography** — har banda apne pichhle step ke event pe react karta hai. Ab agar shaadi cancel ho: jo advance de diya (paisa chala gaya) wo **wapas lena padega** (refund) — seedha "bhool jao" nahi chalega. Aur agar sirf date hold ki thi, paisa nahi diya (CONFIRMED) to bas hold chhod do (release) — refund ki zarurat nahi. **Kya undo karna hai, ye is pe depend karta hai ki ab tak hua kya tha.**

## Kyu (ye problem solve karta hai)
Microservices mein ek hi global DB transaction do services (order + payment) pe nahi chal sakta (alag DBs, alag network). To "sab ek saath commit/rollback" possible nahi. Saga is problem ko todta hai: **chhote-chhote local transactions, aur har ek ka ulta kaam (compensation).** Agar beech mein koi step fail ho, to pehle wale steps ko unke compensation se undo kar do — eventual consistency mil jaati hai bina 2PC ke.
Aur PAID order ka cancel seedha delete/release isliye galat hai kyunki **paisa cut chuka hai** — pehle wo wapas karna padega, warna customer charged + bina product.

## Kaise (step by step)
**Forward flow:**
1. `OrderService.placeOrder` → inventory reserve (REST) → `CONFIRMED` + `OrderConfirmed` event (outbox).
2. payment-service `OrderConfirmedListener.charge` → gateway charge → `payment` row + `PaymentCaptured`/`PaymentFailed` ek local txn (outbox).
3. order-service `PaymentEventListener`:
   - `PaymentCaptured` → `onPaymentCaptured` → sirf `CONFIRMED` ho to `PAID`.
   - `PaymentFailed` → `onPaymentFailed` → `release` stock + `CANCELLED("payment failed: …")`. **(compensation — money kabhi capture nahi hua)**

**Refund saga (PAID cancel):**
4. `OrderService.cancel`: order PAID hai → `requestRefund()` → `REFUND_PENDING` + `OrderRefundPending` event.
5. payment-service `refund`: `findByOrderRef`, sirf `CAPTURED` ho to gateway refund → `markRefunded` → `PaymentRefunded`. **(state guard = idempotency)**
6. order-service `onPaymentRefunded`: sirf `REFUND_PENDING` ho to `release` stock + `REFUNDED`. **Money pehle, stock baad mein.**

**Non-PAID cancel:** `CONFIRMED`/`FAILED` → `release` + `CANCELLED` turant (no money moved). `PENDING`/already-cancelling → not cancellable.

## Flow diagram (forward + both compensations)
```
                       reserve ok           PaymentCaptured
  PENDING ──REST──►  CONFIRMED ──Kafka──►  PAID
     │                  │  │                 │
     │ reserve 409/404  │  │ PaymentFailed   │ customer cancels
     ▼                  │  │ (release stock) │
  REJECTED              │  ▼                 ▼
                        │ CANCELLED      REFUND_PENDING ──OrderRefundPending──► payment refunds CAPTURED
   customer cancels ────┘  ▲                 │                                   │
   (CONFIRMED: release)────┘                 ▼        ◄──PaymentRefunded──────────┘
                                         REFUNDED  (release stock AFTER money back)
```

## Component / event diagram
```
 order-service ──OrderConfirmed──►  Kafka orders.events ──►  payment-service
      ▲                                                          │ charge / refund
      │                                                          ▼
      └────────── Kafka payments.events ◄── PaymentCaptured / PaymentFailed / PaymentRefunded
   (PaymentEventListener: processed_event dedupe by eventId)

 Choreography: koi central orchestrator nahi. Har service apne topic ke events pe react.
 Visibility: distributed tracing (traceparent HTTP + Kafka headers se travel karta).
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Choreography saga** *(ShopFlow)* | har service event pe react | loose coupling, naya consumer free | flow bikhra, tracing chahiye | chhote–medium flows, events natural |
| **Orchestration saga** | central orchestrator har step call kare | flow ek jagah, debug aasan | orchestrator SPOF/coupling, zyada code | lambe/complex flows, strict control |
| **2PC / XA** | global distributed txn | strong atomicity | slow, locks, broker support weak | lagभाग kabhi nahi |
| **Try-Confirm-Cancel (TCC)** | reserve→confirm/cancel API har service pe | explicit compensation API | har service ko 3 ops chahiye | bookings/payments jaise |

## Understanding check
1. CONFIRMED cancel turant CANCELLED, par PAID cancel REFUND_PENDING kyu? (ek hi reason.)
2. "Release stock first, then refund" se exactly kya bura ho sakta hai agar refund fail ho?
3. Refund idempotency "by state" kaise kaam karti hai — guard kahan hai?
4. Choreography ka ek asli faida aur ek asli cost?
5. `RefundFailed` path kahan add karega taaki kuch na khoye?

## Interview mein ye bolo
*"Saga = local transactions + compensations, 2PC ke bina. ShopFlow choreography use karta hai: reserve REST se (sync answer chahiye), baaki Kafka se. PaymentFailed pe stock release + CANCELLED (compensation). PAID order cancel ek refund saga hai — PAID→REFUND_PENDING→REFUNDED — money pehle wapis phir stock, taaki fail refund stock leak na kare. Idempotency state se: payment sirf CAPTURED ho to refund hota hai."*

> Related: idempotency ki teen shaklein → [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/).
</content>
