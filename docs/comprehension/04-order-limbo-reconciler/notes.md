# 04 · Limbo states & the reconciler — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `OrderService.reconcileFailedOrders / countStuckConfirmed`, `OrderReconciler`.

## Kya (one line)
Saga ke beech atke orders (limbo) ko ek background reconciler settle karta hai — **jahan paisa nahi chala (FAILED/PENDING) usay safely auto-cancel, jahan chal sakta hai (CONFIRMED/REFUND_PENDING) usay sirf gauge+alert**, kabhi auto-cancel nahi.

## Easy way (Hinglish analogy)
Restaurant mein order di, par waiter kitchen tak gaya ki nahi pata nahi (timeout). Do case:
- **Paise nahi diye the** (FAILED/PENDING) → manager 2 min baad table saaf kar deta hai, "cancel" — koi nuksan nahi, aur agar kitchen ne sach mein kuch nahi banaya to "release" ka matlab kuch nahi (no-op).
- **Paisa de chuke the** (CONFIRMED→payment in flight) → manager table **nahi** saaf karta blindly — ho sakta khana ban ke aa raha ho. Wo sirf **note karta hai "ye table 10 min se wait kar raha"** aur senior ko bulata hai. Galti se paid order cancel karna = customer ka paisa gaya, khana bhi nahi.

## Kyu (ye problem solve karta hai)
1. **Connection pool:** agar `placeOrder` ek bade `@Transactional` mein hota, to inventory ke HTTP call ke dauraan DB connection block rehta; load pe pool khali → service hang. Isliye har step alag save, aur **status** progress record karta hai.
2. Is separation se **limbo** banta hai (PENDING/FAILED — outcome unknown). Customer ko hamesha ke liye atka nahi chhod sakte → **reconciler** definite answer deta.
3. Par har limbo same nahi: CONFIRMED stuck ko auto-cancel karna = **paid order cancel** ka risk. Isliye money-moved vs not ke hisaab se alag treatment.

## Kaise (step by step)
- `placeOrder`: save `PENDING` → inventory reserve (HTTP). Success→`CONFIRMED`, reject→`REJECTED`, unreachable/breaker→`FAILED` (readiness UP rehta, inventory readiness mein nahi).
- `OrderReconciler.run` `@Scheduled(60s)`:
  - `reconcileFailedOrders(2m)`: `FAILED`+`PENDING` older than grace → `release` (idempotent) + `CANCELLED` + event. Inventory down → batch **break**, agli run retry.
  - `countStuckConfirmed(10m)`: `CONFIRMED`+`REFUND_PENDING` older than longer grace → `AtomicLong` gauge `orders_stuck_confirmed` set (Prometheus cached value padhta, DB query nahi).
- Alert `OrdersStuckConfirmed` gauge pe → human.

## Flow diagram (limbo settlement)
```
 placeOrder (no big @Transactional)
   PENDING ──HTTP reserve──► CONFIRMED / REJECTED
      │                          │
      │ (pod crash)              │ (payment in flight over Kafka)
      ▼                          ▼
  PENDING(stuck)             CONFIRMED(stuck)
      │                          │
   FAILED (inventory unknown)    │
      │                          │
      ▼  reconciler 60s          ▼  reconciler 60s
  older than 2m?             older than 10m?
      │ yes                      │ yes
  release (no-op safe)       count into gauge ──► OrdersStuckConfirmed alert ──► HUMAN
   + CANCELLED                (NEVER auto-cancel: money may have moved)
  (inventory down → stop batch, retry next minute)
```

## Decision diagram (kaunsa limbo kaise)
```
        Is this order's money possibly captured?
                 │                      │
                NO                     YES
        (PENDING, FAILED)        (CONFIRMED, REFUND_PENDING)
                │                      │
      auto-cancel after grace    count + alert, human decides
      (idempotent release)       (auto-cancel could strand a PAID order)
```

## Code + dry run (samajh pakki)

**Asli code:**
```java
// OrderReconciler — har 60s: FAILED/PENDING settle + CONFIRMED stuck count
@Scheduled(fixedDelayString = "${orders.reconcile.delay:60s}")
public void run() {
    orderService.reconcileFailedOrders(reconcileGrace);              // grace 2m
    stuckConfirmed.set(orderService.countStuckConfirmed(stuckGrace)); // grace 10m → gauge
}

// reconcileFailedOrders — no money moved → safe auto-cancel
public int reconcileFailedOrders(Duration grace) {
    var stale = orderRepository.findTop100ByStatusInAndUpdatedAtBefore(
            List.of(OrderStatus.FAILED, OrderStatus.PENDING), Instant.now().minus(grace));
    for (Order order : stale) {
        try {
            inventoryClient.release(order.getOrderRef());   // IDEMPOTENT: kuch reserve nahi hua to no-op
            order.cancel(); saveWithEvent(order);
        } catch (RestClientException | CallNotPermittedException e) {
            break;   // inventory abhi bhi down → batch roko, agli run retry (hammer mat karo)
        }
    }
    ...
}

// countStuckConfirmed — money MOVE ho sakta → sirf GINO, cancel NAHI
public long countStuckConfirmed(Duration grace) {
    return orderRepository.countByStatusInAndUpdatedAtBefore(
            List.of(OrderStatus.CONFIRMED, OrderStatus.REFUND_PENDING), Instant.now().minus(grace));
}
```

**Dry run — inventory down → FAILED limbo settle:**
```
t0   POST /orders → save PENDING → inventory.reserve() THROWS (breaker open) → order.fail() → status FAILED
     (readiness UP — inventory readiness mein nahi; POST ne 503 diya par order FAILED save hua)
t+2m reconciler.run (60s tick): FAILED + updatedAt < now-2m? haan
     → inventoryClient.release(orderRef)  (kuch reserve nahi hua → no-op, safe)
     → order.cancel() → status CANCELLED + OrderCancelled event
     → customer ko definite answer, limbo khatam
 (agar inventory abhi bhi down: release throw → break → agli minute retry)
```

**Dry run — payment-service down → CONFIRMED stuck (auto-cancel NAHI):**
```
t0    order CONFIRMED, OrderConfirmed event gaya, par payment-service down → PaymentCaptured kabhi nahi
t+10m reconciler: countStuckConfirmed → ye order gina → orders_stuck_confirmed gauge ++
      → OrdersStuckConfirmed alert → HUMAN
      ❗ auto-cancel NAHI: payment in-flight ho sakta; cancel karte to PAID order strand ho jaata
t+    payment-service UP → PaymentCaptured → onPaymentCaptured (CONFIRMED?) → PAID  (khud theek)
```
**Seekh:** "har limbo ka owner" ≠ "auto-cancel". Money na chala (FAILED/PENDING) → safe cancel; chal sakta (CONFIRMED) → sirf alert.

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Big @Transactional around remote call** | ek txn mein save+HTTP | simple to read | **pool exhaustion**, stalls under load | kabhi nahi |
| **Step-save + status + reconciler** *(ShopFlow)* | har step alag, background sweep | no long-held conn, no limbo forever | eventual (minutes) settle | default |
| **Auto-cancel all stuck** | reconciler sabko cancel | fully self-healing | **paid order cancel bug** | kabhi nahi (money states) |
| **Orchestrated saga timeout (verify first)** | timeout checks payment then acts | orphans bhi clear | extra cross-service check | STATE §4 open item |

## Understanding check
1. `placeOrder` big `@Transactional` kyu nahi?
2. `release` ki kaunsi property blind retry ko safe banati?
3. FAILED aur long-PENDING "same shape" kyu?
4. CONFIRMED stuck ko auto-cancel na karne ki asli wajah + step-by-step incident?
5. Reconcile grace 2m par stuck grace 10m kyu?

## Interview mein ye bolo
*"Remote call ke around transaction nahi rakhta — connection pool exhaust ho jaata; order status se saga progress track hota. FAILED/PENDING limbo mein koi paisa nahi chala, to reconciler har 60s unhe idempotent release + CANCELLED kar deta (inventory down ho to batch rok ke retry). CONFIRMED/REFUND_PENDING stuck ko main auto-cancel nahi karta — payment in-flight ho sakta, cancel karne se paid order strand ho jaata — wo sirf orders_stuck_confirmed gauge + alert pe jaata, human decide karta. Rule: money na chala ho to auto-cancel safe, chala ho sakta ho to sirf alert."*

> Related: [`../06-slo-burn-rate-bug/`](../06-slo-burn-rate-bug/) (alerting), [`../02-saga-cancel-refund/`](../02-saga-cancel-refund/) (REFUND_PENDING).
</content>
