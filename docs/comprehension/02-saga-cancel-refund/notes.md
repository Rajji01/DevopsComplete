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
5. payment-service `refund`: `findByOrderRef`, sirf `CAPTURED` ho to gateway refund. Approve → `markRefunded` → `PaymentRefunded`. **Decline → payment `CAPTURED` hi rehta → `PaymentRefundFailed`** (Walk 20: fake REFUNDED nahi; redelivery retry karega). **(state guard = idempotency)**
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
      └────────── Kafka payments.events ◄── PaymentCaptured / PaymentFailed / PaymentRefunded / PaymentRefundFailed
   (PaymentEventListener: processed_event dedupe by eventId; PaymentRefundFailed → onRefundFailed, order stays REFUND_PENDING)

 Choreography: koi central orchestrator nahi. Har service apne topic ke events pe react.
 Visibility: distributed tracing (traceparent HTTP + Kafka headers se travel karta).
```

## Code + dry run (samajh pakki)

**Asli code:**
```java
// OrderService.cancel — teen shapes (kitna door saga pahuncha)
public Order cancel(long id, Caller caller) {
    Order order = get(id, caller);
    if (order.isPaid()) {                       // PAID: paisa cut chuka → refund saga
        order.requestRefund();                  // status = REFUND_PENDING
        return saveWithEvent(order);            // event OrderRefundPending → payment-service
    }
    if (!order.isCancellable())                 // PENDING/already-cancelling → 409
        throw new OrderNotCancellableException(id, order.getStatus());
    inventoryClient.release(order.getOrderRef());  // CONFIRMED/FAILED: no money → release + cancel
    order.cancel();
    return saveWithEvent(order);
}

// payment-service OrderConfirmedListener.refund — idempotent BY STATE + decline branch (Walk 20)
private void refund(OrderEvent event) {
    payments.findByOrderRef(event.orderRef())
        .filter(p -> p.getStatus() == Payment.Status.CAPTURED)   // sirf CAPTURED (redeliver = no-op)
        .ifPresent(payment -> {
            var result = gateway.refund(payment.getOrderRef(), payment.getAmount());
            if (result.approved()) {
                payment.markRefunded();                          // CAPTURED → REFUNDED
                outbox.publish(..., "PaymentRefunded", ...);
            } else {                                             // DECLINE: fake REFUNDED NAHI
                outbox.publish(..., "PaymentRefundFailed", ...); // payment CAPTURED hi rehta → retry
            }
        });
}
```

**Dry run — PAID order cancel (approve path):**
```
DELETE /orders/42  (status PAID)
 → cancel: isPaid() → requestRefund() → status REFUND_PENDING → saveWithEvent → OrderRefundPending event
 → payment-service refund(): payment CAPTURED? haan → gateway.refund() approved
       → payment.markRefunded() (CAPTURED→REFUNDED) → PaymentRefunded event
 → order-service onPaymentRefunded(): order REFUND_PENDING? haan → inventoryClient.release() → markRefunded
       → status REFUNDED   (MONEY pehle wapas, STOCK baad mein)
```

**Dry run — refund DECLINE (Walk 20 branch):**
```
... REFUND_PENDING → OrderRefundPending → payment-service refund(): gateway.refund() DECLINED
 → payment CAPTURED hi rehta (fake REFUNDED nahi) → PaymentRefundFailed event
 → order-service onRefundFailed(): order REFUND_PENDING pe ATKA rehta + counter++  (status badalta NAHI)
 → OrdersStuckConfirmed alert (REFUND_PENDING bhi count hota) → human
 → redelivered OrderRefundPending → payment abhi CAPTURED → refund RETRY  (koi stock leak nahi)
```
**Seekh:** money na aaye to stock release nahi (invariant); decline pe system REFUND_PENDING pe honestly atkata hai, fake REFUNDED nahi.

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
5. Refund decline ho jaye to kya hota hai? (Walk 20: payment `CAPTURED` rehta, `PaymentRefundFailed`, order `REFUND_PENDING`, alert pages, redelivery retries — `REFUNDED` kabhi fake nahi hota.)

## Test karo (UI + Postman + curl)
- **React UI:** `lab-console.html` → **Saga-async** panel (order → CONFIRMED → PAID), aur cancel. **Postman:** Lab 3 cancel (DELETE).
- **curl** (FakeGateway charge-limit 100000; PS5 54990×1 approve, ×2 decline):
```bash
ORD(){ curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d "$1"; }
ID=$(ORD '{"sku":"PS5-SLIM","quantity":1}' | jq -r .id); sleep 3                        # → PAID
curl -s -X DELETE http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" | jq .status  # REFUND_PENDING
sleep 3; curl -s http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" | jq .status    # REFUNDED
# payment-fail compensation: qty 2 (109980 > limit) → CONFIRMED then CANCELLED
IDF=$(ORD '{"sku":"PS5-SLIM","quantity":2}' | jq -r .id); sleep 3
curl -s http://localhost:8081/api/v1/orders/$IDF -H "Authorization: Bearer $TOKEN" | jq .status           # CANCELLED
```

## Interview mein ye bolo
*"Saga = local transactions + compensations, 2PC ke bina. ShopFlow choreography use karta hai: reserve REST se (sync answer chahiye), baaki Kafka se. PaymentFailed pe stock release + CANCELLED (compensation). PAID order cancel ek refund saga hai — PAID→REFUND_PENDING→REFUNDED — money pehle wapis phir stock, taaki fail refund stock leak na kare. Idempotency state se: payment sirf CAPTURED ho to refund hota hai. Agar gateway refund decline kare, payment CAPTURED hi rehta aur PaymentRefundFailed emit hota — order REFUND_PENDING pe atka rehta aur alert human ko bulata, hum kabhi fake REFUNDED nahi karte."*

> Related: idempotency ki teen shaklein → [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/).
</content>
