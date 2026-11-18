# 01 · Outbox → Kafka — LIVE walkthrough (concept → code → run)

> Companion to [`notes.md`](notes.md) (concept) + [`comprehension.md`](comprehension.md) (Q&A round).
> Ye file ek baithak ki poori kahani hai: **dual-write problem → outbox → CDC/Debezium → idempotent consumer**, phir **asli stack chala ke** ek order ko `POST` se le kar Kafka tak trace kiya. Padhega to code bhi samajh aayega aur khud chala bhi payega.
>
> Code jisko ye note samjhata hai:
> - `shopflow/shopflow-outbox/src/main/java/com/shopflow/outbox/` — `OutboxPublisher`, `OutboxEvent`, `OutboxRelay`, `OutboxRepository`
> - `shopflow/order-service/.../order/OrderService.java` → `saveWithEvent(...)`
> - `shopflow/notification-service/.../OrderEventListener.java` + `ProcessedEvent.java` (idempotent consumer)

---

## 0. Teen cheezein ek saath (mental map)

```
   PRODUCER side                                  CONSUMER side
   ┌───────────────────────────┐                 ┌─────────────────────────────┐
   │ Transactional Outbox      │                 │ Idempotent consumer         │
   │ order + event = 1 DB txn  │── Kafka ───────▶│ eventId -> processed_event  │
   │ relay poll → publish      │  (at-least-once)│ duplicate = PK clash = skip │
   └───────────────────────────┘  duplicate      └─────────────────────────────┘
        "event MISS nahi hoga"      mumkin             "event DO BAAR nahi chalega"

   (Outbox ka alternative = CDC/Debezium: relay code ki jagah DB ka WAL tap karo)

   Outbox/CDC  +  Idempotent consumer  =  effectively-exactly-once
```

Ek line mein: **exactly-once network pe exist nahi karta.** Hum use *at-least-once delivery* + *idempotent consumer* se banate hain.

---

## 1. Problem: dual-write

Order banne pe order-service ko do kaam karne hain:
1. `orders` table mein order save
2. Kafka pe `OrderConfirmed` event (taaki payment/notification react karein)

Seedha (galat):
```java
orderRepository.save(order);     // DB
kafka.send("orders.events", e);  // Kafka  ← do ALAG systems, ek atomic nahi
```

Beech mein crash:

| Crash kab | Nateeja |
|---|---|
| save ke baad, send se pehle | order DB mein, event kabhi nahi gaya → payment ko pata hi nahi → order hamesha atka |
| send ke baad, commit se pehle | event chala gaya, order DB mein nahi → phantom charge |

Ye **dual-write problem**. Fix = outbox.

---

## 2. Solution: Transactional Outbox (code map)

**Idea:** event ko Kafka pe mat bhejo turant — use **usi DB transaction mein** ek `outbox_event` table mein likho jisme order likha. Ek transaction = dono ya dono nahi (atomic). Phir ek background **relay** us table ko padh ke Kafka pe bhejta hai.

```
  ┌──── ek hi DB transaction (OrderService.saveWithEvent) ────┐
  │  orders table   ← order row                               │  crash yahan? poora rollback. Safe.
  │  outbox_event   ← OrderConfirmed row (published_at = NULL) │
  └───────────────────────────────────────────────────────────┘
            │ commit
            ▼
   OutboxRelay (@Scheduled 1s)  ── findUnpublished (SKIP LOCKED) ──▶ kafka.send().get(5s) ack ──▶ markPublished()
```

### (a) `OutboxPublisher.publish()` — apni koi txn NAHI (jaan-bujh ke)
`shopflow-outbox/.../OutboxPublisher.java`
```java
public void publish(String topic, String aggregateType, String aggregateId, String type, Object event) {
    outboxRepository.save(new OutboxEvent(topic, aggregateType, aggregateId, type, toJson(event)));
}
```
Plain `save()` — isliye ye **caller ki transaction** mein chalta hai. Apni txn hoti to alag commit ho jaati aur dual-write wapas aa jaata.

### (b) Caller atomic likhta hai — `OrderService.saveWithEvent(...)`
`order-service/.../order/OrderService.java`
```java
return transactionTemplate.execute(status -> {          // ← ek transaction
    Order persisted = orderRepository.save(order);        //   order row
    OrderEvent event = OrderEvent.from(persisted);
    outbox.publish(OrderEvent.TOPIC, "Order", persisted.getOrderRef(), event.type(), event); // outbox row
    return persisted;
});                                                       // ← dono commit ya dono rollback
```
**Dhyaan:** `@Transactional` ke bajaye `TransactionTemplate` — kyunki same-class se annotated method call pe Spring ka proxy bypass ho jaata hai, txn lagti hi nahi. (Yahi trap ledger-service Walk 99 mein bhi aaya tha — `LedgerService.record()` isiliye `TransactionTemplate` use karta hai.)

### (c) `OutboxEvent` — table + ek optimization
`shopflow-outbox/.../OutboxEvent.java`
- `@Table(name = "outbox_event")`, `aggregate_id` = Kafka message **key** (ek order ke events ek partition mein, order mein).
- `published_at == NULL` = abhi Kafka pe nahi gaya.
- `implements Persistable<UUID>`, `isNew(){return true;}` → assigned-UUID `@Id` hone par Spring Data har `save()` se pehle ek SELECT ("ye naya hai ya update?") maarta; `isNew=true` wo SELECT bachaa ke seedha INSERT karwata (insert-only table pe perfect).

### (d) `OutboxRelay.publishPending()` — poll → send → ack → mark
`shopflow-outbox/.../OutboxRelay.java`
```java
// @Scheduled(1s)
for (OutboxEvent e : repo.findUnpublished(PageRequest.of(0, 100))) {
    kafkaTemplate.send(e.getTopic(), e.getAggregateId(), e.getPayload()).get(5, SECONDS); // broker ACK ka wait
    e.markPublished();
    // koi bhi send fail -> log + return (batch roko; order bache; agli run retry)
}
```
- `kafka.send(...).get(5s)` — **ack ka wait zaroori**, warna "bheja" maan ke markPublished kar dete aur Kafka blip pe message silently lost.
- `OutboxRepository.findUnpublished` pe `FOR UPDATE SKIP LOCKED` → kai pods ek saath relay chalayein to har pod **alag** rows uthata, ek row do baar nahi jaati (multi-pod dedup).

### (e) Safety net
Gauge `outbox_unpublished` (= `countByPublishedAtIsNull`) → alert **`OutboxBacklogGrowing`** (>100 for 5m). Matlab publish-leg down hai jabki API abhi bhi 201 de raha hoga — producer-side se "Kafka down hai" ka signal.

---

## 3. Dry-run: ek asli order (jo humne live chalaya)

`POST /api/v1/orders { "sku": "PIXEL-9", "quantity": 1 }` → orderRef `08160fc9…`

```
 t0  POST /orders (JWT: alice)                    order-service
      │  placeOrder: inventory reserve PIXEL-9 ok → status CONFIRMED
      │  saveWithEvent  ┌─ orders row (id=4, CONFIRMED) ─┐  ONE txn
      │                 └─ outbox_event: OrderConfirmed  ┘  (published_at NULL)
      │  HTTP 201 { status: CONFIRMED }  ← client ko turant, Kafka ka wait nahi
      ▼
 t0+~1s  OutboxRelay poll → kafka.send("orders.events", key=08160fc9, OrderConfirmed).get(5s) ✔
         → markPublished()  (outbox row: published_at set)
      ▼
      ├──▶ notification-service  OrderEventListener → "EMAIL: order 08160fc9 … PIXEL-9 is confirmed"
      │                          (processed_event mein eventId likha — dubara aaye to skip)
      └──▶ payment-service       OrderConfirmedListener → gateway → "CAPTURED 74999.00"
                                  → apna outbox: PaymentCaptured
                                        │
 t0+~1.3s                               ▼  order-service PaymentEventListener consume
                                  order 4 → PAID ; saveWithEvent → outbox: OrderPaid
                                        │
                                        ├──▶ notification: "EMAIL: order 08160fc9 is now PAID"
                                        └──▶ (saga complete)
```

Asli DB snapshot (jo humne dekha):
```
orders:        id=4  order_ref=08160fc9…  sku=PIXEL-9  status=PAID
outbox_event:  OrderConfirmed  published=t
               OrderPaid       published=t
```

### Dono saga raaste ek saath dikhe (ye samajhna zaroori)
| Order | Amount | Payment gateway | Order final | Kyun |
|---|---|---|---|---|
| PIXEL-9 × 1 | ₹74,999 | **CAPTURED** | **PAID** | < ₹1,00,000 card limit |
| IPHONE-15 × 2 | ₹1,59,800 | **FAILED** (`card limit exceeded`) | **CANCELLED** + stock released | > ₹1,00,000 limit |

CANCELLED koi bug nahi — **saga ka compensation path** hai: payment fail → order cancel + reserved stock wapas. Isiliye fail hone wale order ke outbox mein `OrderConfirmed` **aur** `OrderCancelled` dono events dikhe.

---

## 4. CDC / Debezium — outbox ka alternative (1 min)

Outbox event humne **code se** banaya (`outbox.publish`) aur **apne relay** se bheja. CDC yahi kaam **app ko chhue bina** karta:

- Har DB (Postgres) change ko pehle apne **WAL (Write-Ahead Log)** mein likhta hai (crash recovery ke liye).
- **Debezium** us WAL ko tap karta (Postgres replication slot se), har committed row-change ko khud Kafka event bana deta — `before`/`after` ke saath. App sirf table pe INSERT karta hai; koi event-code, koi relay nahi.

| | Outbox (ShopFlow) | CDC / Debezium |
|---|---|---|
| Event kaun banata | app code (`outbox.publish`) | Debezium (WAL se) |
| Kafka pe kaun bhejta | tumhara `OutboxRelay` (poll) | Debezium (Kafka Connect) |
| Event ka shape | clean business event (`OrderConfirmed`) | raw table row (before/after) — schema leak |
| Extra infra | sirf table + app ke andar scheduler | Kafka Connect cluster + Debezium |

Dono **at-least-once** dete hain, dono dual-write solve karte. ShopFlow ne **polling outbox** chuna — simple, zero extra infra. Bade scale pe polling DB pe load daale to Debezium jeetta (outbox table ko rakh ke relay ki jagah Debezium **Outbox Event Router** laga do — clean event bhi, relay bhi nahi).

**Yaad:** *WAL = DB already apne har change ka perfect ordered log rakhta hai; CDC bas us log ko tap kar leta hai.*

---

## 5. Idempotent consumer — duplicate se bachao (`processed_event`)

At-least-once ka matlab **kabhi-kabhi same event do baar** (relay crash before markPublished, ya Kafka redelivery before offset commit). Bina handle kiye: notification = do email, payment = double charge. 💀

Fix — `notification-service/.../OrderEventListener.java`:
```java
@KafkaListener(topics = "orders.events", groupId = "notification-service")
@Transactional                                           // ← ek transaction mein side-effect + record
public void onOrderEvent(String payload) {
    OrderEvent event = ...;
    if (processedEvents.existsById(event.eventId())) {    // (a) pehle aaya? → skip
        meterRegistry.counter("notifications.duplicates").increment();
        return;
    }
    notify(event);                                        // (b) asli kaam
    processedEvents.save(new ProcessedEvent(event.eventId(), event.orderRef())); // (c) id record
}
```
- `@Transactional`: `notify()` + `save(id)` **atomic** — dono commit ya dono rollback. Beech mein crash = redeliver pe saaf dobara.
- `existsById` = optimization; asli guarantee `processed_event` ka **primary key** (duplicate `save` = PK clash).
- `ProcessedEvent` bhi `Persistable isNew()=true` (wahi INSERT optimization).
- `ProcessedEventCleanup` purani rows retention (14d) baad delete karta — Kafka ka replay window beet gaya to id yaad rakhne ka fayda nahi.

**Yaad:** *Outbox = miss nahi hoga. Inbox (idempotent consumer) = do baar nahi chalega. Dono chahiye.*

---

## 6. Khud chalao (reproduce)

> Pre-req: Docker Desktop on. Pehli baar `up` 4 Java services build karega (~5 min).

```bash
cd shopflow
docker compose up -d --build          # poora stack (postgres, kafka, keycloak, 4 services, monitoring)
# order-service ready: curl http://localhost:8081/actuator/health/readiness  -> {"status":"UP"}
```

**1) Token (Keycloak, user alice):**
```
POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token
Body (x-www-form-urlencoded): grant_type=password  client_id=shopflow-web  username=alice  password=alice
```
→ `access_token` copy.

**2) Order banao (Postman/curl):**
```
POST http://localhost:8081/api/v1/orders
Headers: Authorization: Bearer <token> | Content-Type: application/json | Idempotency-Key: demo-1
Body: { "sku": "PIXEL-9", "quantity": 1 }
```
→ `201 { status: CONFIRMED }`.  (SKUs: `PIXEL-9`, `AIRPODS-PRO` = PAID; `IPHONE-15 × 2` = payment fail → CANCELLED.)

**3) DB mein dono rows dekho (yahi lesson):**
```bash
docker exec shopflow-postgres-1 psql -U orders -d orders -c \
  "SELECT id, order_ref, sku, status FROM orders ORDER BY id DESC LIMIT 5;"
docker exec shopflow-postgres-1 psql -U orders -d orders -c \
  "SELECT event_type, (published_at IS NOT NULL) AS published FROM outbox_event ORDER BY id DESC LIMIT 5;"
```

**4) Kafka tak gaya? (consumers ne khaya?):**
```bash
docker logs shopflow-notification-service-1 2>&1 | grep <orderRef>
docker logs shopflow-payment-service-1      2>&1 | grep <orderRef>
```

Tests (JDK 21 ho to) jo yahi prove karte:
- `OrderServiceApplicationTests#publishesOrderEventThroughTheOutbox` — `@EmbeddedKafka` se event padh ke verify.
- `OutboxCleanupTest#deletesOnlyPublishedEventsOlderThanRetention` — cleanup (published hi delete, unpublished kabhi nahi).

---

## 7. Is session mein mile environment bugs (honesty log)

Stack pehli baar Windows pe chalaya to 4 cheezein tooti — sab asli, fix ho gayi:

1. **`k8s/base/postgres/init-db.sh` CRLF line endings** → container mein `/bin/sh^M: bad interpreter`, postgres init fail (exit 126). Fix: CRLF→LF. (Windows checkout pe kisi ka bhi stack isse tootega — `.gitattributes` se `*.sh eol=lf` pin karna chahiye.)
2. **`order-service/application.yml` mis-indentation** → `datasource`/`jpa`/`mvc` galti se `jwt:` ke neeche nested the (hone chahiye `spring:` ke neeche). Isliye `spring.datasource.url` khaali → "Failed to determine a suitable driver class", app start hi nahi hui. Tests nahi pakadte kyunki wo `application-test.yml` (H2) use karte — **classic "green that is red"**. Fix: wapas `spring:` ke neeche.
3. **Keycloak realm: alice/bob ke `firstName`/`lastName` missing** → Keycloak 26 ka declarative user-profile in dono ko required banata, login pe VERIFY_PROFILE trigger → `invalid_grant: Account is not fully set up`. Fix: realm JSON mein naam add.
4. **`localhost` issuer container ke andar** → order-service JWKS ko `http://localhost:8180` pe dhoondhta, par container ke andar `localhost` = khud container (compose ka `extra_hosts: localhost:host-gateway` kaam nahi karta, kyunki `/etc/hosts` mein built-in `127.0.0.1 localhost` pehle aata hai) → "Connection refused". Fix: `KC_HOSTNAME` + `JWT_ISSUER_URI` dono ko `http://host.docker.internal:8180` pe align (ek URL jo host se bhi aur container se bhi reachable, aur token ka `iss` isse match karta).

---

## 8. Interview lines (ek saans mein)

- **Outbox:** "DB aur Kafka atomic nahi — dual-write. Humne event ko business row ke saath ek local transaction mein `outbox_event` table mein likha; ek relay `FOR UPDATE SKIP LOCKED` se poll karke, broker ack ke baad Kafka pe bhejta aur `published_at` set karta. At-least-once, isliye consumers idempotent."
- **CDC:** "Alternative Debezium — Postgres ke WAL/replication slot se change capture karke Kafka pe daalta, app code zero. Trade-off: raw table shape leak + Kafka Connect ka overhead; humare scale pe polling simple jeetta. Volume badhe to outbox table rakh ke relay ki jagah Debezium Outbox Router."
- **Idempotent consumer:** "Delivery at-least-once hai, isliye har consumer event ki unique id ko side-effect ke same transaction mein `processed_event` mein likhta; duplicate = PK clash = skip. Producer at-least-once + consumer idempotent = effectively-once."

---

*Related:* [`notes.md`](notes.md) · [`comprehension.md`](comprehension.md) · [`../03-idempotency-three-ways/notes.md`](../03-idempotency-three-ways/notes.md) · [`../02-saga-cancel-refund/notes.md`](../02-saga-cancel-refund/notes.md)
