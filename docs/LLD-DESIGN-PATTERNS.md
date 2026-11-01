# LLD + Design Patterns — ShopFlow ke asli code se (revision-ready)

> Har pattern: **kya → kahan (`file`) → kyu yahan → snippet → interview line.** Sab **real ShopFlow code** se — ratna nahi, samajhna. Jodi: concepts [`EASY-NOTES.md`](EASY-NOTES.md), flows [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md), DB/JPA [`INDEXING-HIBERNATE.md`](INDEXING-HIBERNATE.md).

## Kaise use karo
Interview mein "design patterns jaante ho?" aaye to **GoF naam ratne ke bajaye** bolo: *"mere project mein `PaymentGateway` ek Strategy hai, kyunki..."*. Neeche har pattern ka **"mere project mein kahan"** diya hai.

## Fehrist
1. [Strategy — PaymentGateway](#strategy)
2. [Transactional Outbox](#outbox)
3. [Idempotent Receiver](#idempotent)
4. [Choreography Saga + Compensating Transaction](#saga)
5. [Factory Method — events + aggregates](#factory)
6. [Aggregate + encapsulated state machine — Order](#aggregate)
7. [Optimistic Locking (@Version)](#optlock)
8. [Repository + atomic update](#repo)
9. [Template Method — exception handler](#template)
10. [DTO / immutable value objects (records)](#dto)
11. [Anti-corruption copy — consumer-side event](#acl)
12. [Background reconciler (sweeper)](#reconciler)
13. [Circuit Breaker + Retry (resilience)](#resilience)
14. [Dependency Injection + DIP](#di)
15. [SOLID — kahan dikhta hai](#solid)

---

## 1. Strategy — `PaymentGateway` <a id="strategy"></a>
**Kya:** ek algorithm/behaviour ko interface ke peeche rakho; implementation runtime pe swap ho.
**Kahan:** `payment-service/PaymentGateway.java` (interface) + `FakePaymentGateway` (impl).
**Kyu:** asli processor (Razorpay/Stripe) ek **one-class change** ho — baaki code (`OrderConfirmedListener`) interface pe depend karta hai, concrete pe nahi.
```java
public interface PaymentGateway {
    record Result(boolean approved, String reason) { ... }
    Result charge(String orderRef, BigDecimal amount);
    Result refund(String orderRef, BigDecimal amount);
}
```
**Interview line:** "Payment processor ko `PaymentGateway` Strategy ke peeche rakha — test mein `FakePaymentGateway`, prod mein real, aur `charge` ka `Result` check hota hai (Walk 20: refund ka result pehle ignore ho raha tha → money bug)."

## 2. Transactional Outbox <a id="outbox"></a>
**Kya:** business change + "event bhejna hai" **ek hi DB transaction** mein; alag relay baad mein publish kare.
**Kahan:** `shopflow-outbox/OutboxPublisher` (same-tx write), `OutboxRelay` (poll + Kafka), `OrderService.saveWithEvent`.
**Kyu:** dual-write problem (DB phir Kafka) atomic nahi → crash pe event lost. Ek DB tx atomic hai.
```java
Order saved = transactionTemplate.execute(status -> {
    Order persisted = orderRepository.save(order);
    outbox.publish(OrderEvent.TOPIC, "Order", persisted.getOrderRef(), event.type(), event); // same tx
    return persisted;
});
```
**Interview line:** "Outbox se at-least-once guarantee; isliye consumers idempotent (processed_event)."

## 3. Idempotent Receiver <a id="idempotent"></a>
**Kya:** duplicate message ka effect ek hi baar.
**Kahan:** `notification/OrderEventListener` + `ProcessedEvent` (PK=eventId); `order/PaymentEventListener`; payment `existsById(eventId)`; order-side **state-guard** (`filter(Order::isConfirmed)`).
**Kyu:** Kafka at-least-once + outbox re-send → duplicates aayenge hi.
```java
if (processedEvents.existsById(event.eventId())) { /* skip */ return; }
notify(event);
processedEvents.save(new ProcessedEvent(event.eventId(), event.orderRef())); // same tx as side effect
```
**Interview line:** "Teen tarah: client Idempotency-Key, reservation orderRef, consumer processed_event + status-guard."

## 4. Choreography Saga + Compensating Transaction <a id="saga"></a>
**Kya:** distributed transaction ko chhote local steps mein todo; koi central orchestrator nahi — har service pichhle event pe react kare. Fail pe **ulta step** (compensation).
**Kahan:** `OrderConfirmedListener` (charge on OrderConfirmed), `PaymentEventListener` (PAID/CANCELLED), `OrderService.onPaymentFailed` (compensation = stock release + cancel).
**Kyu:** 2PC mahanga/nazuk; saga loosely coupled + resilient.
```java
public void onPaymentFailed(String orderRef, String reason) {   // compensation
    orderRepository.findByOrderRef(orderRef).filter(Order::isConfirmed).ifPresent(order -> {
        inventoryClient.release(orderRef);
        order.cancel("payment failed: " + reason);
        saveWithEvent(order);
    });
}
```
**Interview line:** "Sync part (reserve, customer wait kar raha) orchestrate; async part (payment, notification) choreograph — real-world dono."

## 5. Factory Method — events + aggregates <a id="factory"></a>
**Kya:** object banane ka naam-wala static method (constructor ke bajaye), intent clear + invariants set.
**Kahan:** `Order.pending(...)`, `OrderEvent.from(order)`, `PaymentEvent.captured/failed/refunded/refundFailed(...)`.
```java
static PaymentEvent captured(String orderRef, BigDecimal amount) {
    return new PaymentEvent(UUID.randomUUID(), "PaymentCaptured", orderRef, amount, null, Instant.now());
}
```
**Interview line:** "`OrderEvent.from` event-type ko enum se derive karta hai (`pascal()`) — type ek contract hai (Walk 7: `OrderRefund_pending` bug tha)."

## 6. Aggregate + encapsulated state machine — `Order` <a id="aggregate"></a>
**Kya:** ek aggregate apni state ka maalik; bahar se random setters nahi — sirf valid transitions (package-private methods).
**Kahan:** `order/Order.java` — `confirm()/reject()/fail()/cancel()/markPaid()/requestRefund()/markRefunded()`, `isCancellable()/isConfirmed()` guards; koi public setter nahi.
**Kyu:** invalid state transition code se hi roko (compile/encapsulation), runtime check se nahi.
```java
void confirm() { status = OrderStatus.CONFIRMED; }
boolean isCancellable() { return status == OrderStatus.CONFIRMED || status == OrderStatus.FAILED; }
```
**Interview line:** "Order ek aggregate root hai; state transitions uske andar, isliye koi `setStatus(PAID)` galat jagah se nahi kar sakta."

## 7. Optimistic Locking (`@Version`) <a id="optlock"></a>
**Kya:** concurrent update detect karo bina lock pakde — version field.
**Kahan:** `Order.version` (`@Version`); `GlobalExceptionHandler` → `OptimisticLockingFailureException` → **409**.
**Kyu:** do concurrent cancel → ek jeete, doosra 409 (lost update roka), 500 nahi.
**Interview line:** "Low-contention pe optimistic (@Version, WHERE version=); high-contention/queue pe pessimistic (outbox `FOR UPDATE SKIP LOCKED`)." (detail: INDEXING-HIBERNATE §10–11)

## 8. Repository + atomic update <a id="repo"></a>
**Kya:** persistence ko interface ke peeche; custom atomic ops jahan zaroori.
**Kahan:** `ProductRepository.decrementStock` (`@Modifying @Query ... WHERE quantity >= :qty`), `OrderRepository` derived queries, `OutboxRepository` (SKIP LOCKED).
**Interview line:** "Oversell read-modify-write se nahi — ek atomic conditional UPDATE, DB row-lock serialise karta."

## 9. Template Method — exception handling <a id="template"></a>
**Kya:** base class skeleton deta hai, tum hook override karo.
**Kahan:** `GlobalExceptionHandler extends ResponseEntityExceptionHandler` — `handleMethodArgumentNotValid` / `handleHandlerMethodValidationException` override, baaki Spring ka skeleton.
**Interview line:** "RFC 7807 problem+json har error ke liye; validation errors parent class ke template method override karke list kiye."

## 10. DTO / immutable value objects (records) <a id="dto"></a>
**Kya:** API/transport ke liye immutable data carriers; entity ko leak mat karo.
**Kahan:** `OrderController.CreateOrderRequest` / `OrderResponse`, `Caller`, `PaymentEvent`, `OrderEvent` — sab Java `record`.
**Kyu:** immutability (thread-safe, no accidental mutation), entity↔API decoupling.
**Interview line:** "Entity (`Order`) bahar expose nahi; `OrderResponse.from(order)` mapping — DB shape aur API contract alag."

## 11. Anti-corruption copy — consumer-side event <a id="acl"></a>
**Kya:** har service producer ke event ki **apni copy** rakhta hai (`@JsonIgnoreProperties(ignoreUnknown=true)`), producer ke class pe depend nahi.
**Kahan:** `notification/OrderEvent`, `payment/OrderEvent`, `order/payment/PaymentEvent` — alag-alag copies.
**Kyu:** schema evolution — producer field add kare to consumer break na ho; services independently deployable.
**Interview line:** "Shared event-jar nahi — har consumer apni copy + ignore-unknown; loose coupling + independent deploy."

## 12. Background reconciler (sweeper) <a id="reconciler"></a>
**Kya:** periodic job jo "limbo" states ko settle kare.
**Kahan:** `OrderReconciler` (@Scheduled) → `reconcileFailedOrders` (FAILED/PENDING → release+cancel), `countStuckConfirmed` (gauge → alert).
**Kyu:** har async failure ka ek owner — "outcome unknown" ko timeout ke baad resolve, ya (CONFIRMED/REFUND_PENDING) human ko alert (money move ho sakta).
**Interview line:** "Every limbo needs an owner — par 'owner' ka matlab auto-cancel nahi; paisa move ho sakta to human + runbook (Walk 7)."

## 13. Circuit Breaker + Retry (resilience) <a id="resilience"></a>
**Kya:** downstream fail pe fail-fast + bounded retry.
**Kahan:** `InventoryClient` — `@Retry(CircuitBreaker(call))`; config `application.yml resilience4j:`.
**Interview line:** "Retry sirf transient (5xx/timeout) pe; breaker 50%/10-calls pe open → fail-fast, thread pile-up/cascading failure roka."

## 14. Dependency Injection + DIP <a id="di"></a>
**Kya:** dependencies constructor se inject; high-level modules abstractions pe depend karein, concretes pe nahi.
**Kahan:** har `@Service`/`@Component` constructor injection (`OrderService(orderRepository, outbox, inventoryClient, ...)`); `OrderConfirmedListener` `PaymentGateway` interface pe depend.
**Kyu:** testability (WireMock/fake inject) + loose coupling.
**Interview line:** "Field-injection nahi, constructor-injection — final fields, mandatory deps clear, test mein inject easy."

## 15. SOLID — kahan dikhta hai <a id="solid"></a>
- **S** (SRP): har service ek kaam — order-service orchestration, inventory stock, payment charge, notification email. ArchUnit (`ArchitectureTest`) rules isse enforce karti hain ("controllers repositories ko touch nahi karte").
- **O** (OCP): naya payment-event type add karo (`PaymentRefundFailed`, Walk 20) — listener switch mein ek case, baaki untouched.
- **L** (LSP): `FakePaymentGateway` `PaymentGateway` ko bina surprise ke replace karta.
- **I** (ISP): `PaymentGateway` chhota (charge/refund) — fat interface nahi.
- **D** (DIP): listeners/services interfaces (`PaymentGateway`, repositories) pe depend.
**Interview line:** "ArchUnit se SRP/layering build mein enforce — 'controllers don't touch repositories' rule kabhi thakta nahi (Walk 5)."

---
### Related
- Kyu ye decisions aise hain (bug stories): [`SELF-WALK.md`](SELF-WALK.md) · flows: [`VISUAL-FLOWS.md`](VISUAL-FLOWS.md) · DB/JPA LLD: [`INDEXING-HIBERNATE.md`](INDEXING-HIBERNATE.md) · interactive: [`../labs/comprehension.html`](../labs/comprehension.html)
