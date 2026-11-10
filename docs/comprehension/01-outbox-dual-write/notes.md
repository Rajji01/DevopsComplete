# 01 · Transactional outbox — concept notes (kya / kyu / kaise)

> Isi topic ka comprehension round: [`comprehension.md`](comprehension.md). Code: `shopflow/shopflow-outbox/` + `order-service/…/OrderService.java#saveWithEvent`.
> **Live hands-on** (stack chala ke `POST` se Kafka tak trace + CDC/Debezium + idempotent consumer, ek jagah): [`LIVE-WALKTHROUGH.md`](LIVE-WALKTHROUGH.md).

## Kya (one line)
Business row **aur** uska event ek hi DB transaction mein ek `outbox_event` table mein likho; ek alag relay baad mein us table ko padh ke Kafka pe publish karta hai. Isse "DB bhi update, Kafka pe bhi event" — dono ya to saath hote hain ya dono nahi.

## Easy way (Hinglish analogy)
Socho tu courier bhejta hai. **Galat tarika:** parcel bana ke seedha courier wale ko de do — agar courier wala us waqt chhutti pe hai (Kafka down), parcel kho gaya, register mein kahin likha hi nahi.
**Outbox tarika:** tu ek hi baar mein (i) parcel banata hai aur (ii) apni **register (DB)** mein entry daalta hai — dono ek saath. Ek delivery-boy (relay) har minute register dekhta hai aur jo parcel abhi tak nahi bheje wo courier ko deta hai. Courier band tha? Register mein entry padi hai, kal bhej dega. Kuch khoya nahi.
Register = `outbox_event` table. Delivery-boy = `OutboxRelay`. Courier = Kafka.

## Kyu (ye problem solve karta hai)
**Dual-write problem:** DB aur Kafka do alag systems hain; inke beech koi transaction nahi hota. Agar tu `db.save()` phir `kafka.send()` karega, to beech mein crash/Kafka-blip = order to ban gaya par event kabhi nahi gaya → payment-service ne charge hi nahi kiya, order chup-chaap unpaid pada hai. Line ulti kar do (pehle send phir save) to ulta hole: event chala gaya par order save nahi hua.
Bina outbox ke koi bhi "DB + message-broker dono update karo" wala flow silently inconsistent ho sakta hai. Outbox dono ko **ek local DB transaction** bana deta hai (jo atomic hai), aur publish ko async kar deta hai.

## Kaise (step by step, file paths ke saath)
1. **Write (one txn):** `OrderService.saveWithEvent` ek `TransactionTemplate` mein — order row save + `outbox.publish(topic, "Order", orderRef, type, event)` (ye `outbox_event` row insert karta hai). Dono ek transaction → saath commit.
   - `@Transactional` **nahi** use kiya kyunki wo proxy-based hai aur same-class private method call pe silently bina transaction chal jaata → isliye `TransactionTemplate` (comment OrderService mein likha hai).
2. **Relay (async):** `OutboxRelay.publishPending` — `@Scheduled(1s)`, `@Transactional`. `findUnpublished` se 100 ka batch; har row pe `kafkaTemplate.send(topic, aggregateId, payload).get(5, SECONDS)` — **broker ack ka wait** — phir `markPublished()`. Fail hua to log karke `return` (batch rok do, order bache, agli run retry).
   - **Multi-pod safe:** `findUnpublished` `@Lock(PESSIMISTIC_WRITE)` + `lock.timeout=-2` → Postgres **`FOR UPDATE SKIP LOCKED`**. HPA se kai pods relay chalate; har pod locked rows skip karke **disjoint batch** leta → ek row do baar publish nahi, aur pods ek doosre ka wait nahi karte.
3. **Key = aggregateId = orderRef** → ek order ke events ek partition mein, order mein.
4. **At-least-once:** send ke baad par markPublished se pehle pod mara → row dobara publish hogi → duplicate. Isliye **har consumer idempotent** (`processed_event` eventId pe, ya payment PK = eventId).
5. **Housekeeping:** published rows 7 din baad cron se delete; unpublished kabhi nahi. Gauge `outbox_unpublished = countByPublishedAtIsNull` → alert `OutboxBacklogGrowing` (>100 for 5m) = "publish leg down" jabki API abhi 201 de raha.

## Flow diagram (happy path + failure)
```
POST /orders
    │
    ▼
┌───────────────────────────── ONE Postgres transaction ─────────────────────────────┐
│  INSERT order (status=CONFIRMED)        INSERT outbox_event (published_at = NULL)    │
└──────────────────────────────────── commit together ───────────────────────────────┘
    │                                                        (every 1s, separate txn)
    │                                                 ┌──────────────────────────────┐
    └──201 to customer (event not sent yet) ─────────│ OutboxRelay.publishPending    │
                                                      │  send(topic,key,payload).get()│──► Kafka orders.events
                                                      │  ack? → markPublished()       │        │
                                                      │  fail? → return (retry next)  │        ▼
                                                      └──────────────────────────────┘   payment / notification
                                                                                         (dedupe on eventId)

Kafka DOWN:  write txn still commits → 201 → relay send() fails → row stays NULL
             → outbox_unpublished climbs → alert. Kafka UP → next run drains. Nothing lost.
```

## Component diagram
```
                 ┌──────────────── order-service ────────────────┐
  customer ──►   │ OrderService.saveWithEvent (TransactionTemplate)│
                 │        │                 │                      │
                 │   orders table     outbox_event table          │
                 │                          ▲                      │
                 │                    OutboxRelay @Scheduled(1s)    │
                 └───────────────────────────│──────────────────── ┘
                                              ▼  acks=all
                                        ┌──────────┐
                                        │  Kafka   │  orders.events (key=orderRef)
                                        └────┬─────┘
                                  ┌──────────┴───────────┐
                                  ▼                      ▼
                         payment-service        notification-service
                         (PK = eventId)         (processed_event dedupe)
```

## Code + dry run (samajh pakki)

**Asli code (do tukde):**
```java
// OrderService.saveWithEvent — order row + outbox row EK local transaction mein
Order saveWithEvent(Order order) {
    Order saved = transactionTemplate.execute(status -> {        // @Transactional NAHI (proxy trap) → template
        Order persisted = orderRepository.save(order);           // (1) orders row
        OrderEvent event = OrderEvent.from(persisted);
        outbox.publish(OrderEvent.TOPIC, "Order",
                       persisted.getOrderRef(), event.type(), event);  // (2) outbox_event row (SAME txn)
        return persisted;                                        // dono saath commit, ya dono nahi
    });
    count(saved);                                                // metric
    return saved;
}

// OutboxRelay.publishPending — har 1s, alag txn, multi-pod safe
@Scheduled(fixedDelayString = "${outbox.relay.delay:1s}")
@Transactional
public void publishPending() {
    var batch = outboxRepository.findUnpublished(PageRequest.of(0, 100));  // FOR UPDATE SKIP LOCKED
    for (OutboxEvent e : batch) {
        try {
            kafkaTemplate.send(e.getTopic(), e.getAggregateId(), e.getPayload())
                         .get(5, TimeUnit.SECONDS);              // broker ACK ka wait
            e.markPublished();                                   // published_at set (dirty-check update)
        } catch (Exception ex) {
            return;                                              // fail → batch roko, agli run retry (order bache)
        }
    }
}
```

**Dry run — ek order (`PS5-SLIM` ×1), happy path:**
```
t0  POST /orders → placeOrder: save PENDING → inventory.reserve() ok → order.confirm() (status=CONFIRMED)
t0  saveWithEvent → TransactionTemplate:
       INSERT orders(status=CONFIRMED)         ┐ ek hi
       INSERT outbox_event(type=OrderConfirmed,│ transaction
                           published_at=NULL)  ┘ → COMMIT
    → 201 client ko (event abhi Kafka pe nahi gaya)
t≤1s relay.publishPending → findUnpublished (SKIP LOCKED) → wo row
       kafkaTemplate.send("orders.events", key=orderRef, payload).get(5s) → ACK
       e.markPublished() → published_at = now
t+   payment-service consume OrderConfirmed → charge → PaymentCaptured → order PAID
```

**Dry run — Kafka DOWN at t≤1s (yahi outbox ka point):**
```
t0  ...same as above → orders+outbox row COMMIT → 201   (order SAFE in DB)
t≤1s relay.send(...) → THROWS (broker down) → catch → return
       outbox_event.published_at abhi bhi NULL → outbox_unpublished gauge ++
    (relay har 1s retry karta; OutboxBacklogGrowing >100 for 5m pe alert)
tN  Kafka UP → agli run findUnpublished → send → ACK → markPublished → backlog drains
    → consumer ko ab event milta (at-least-once; duplicate aaye to processed_event dedupe)
```
**Seekh:** 201 ka matlab sirf "DB commit hua" — downstream alag leg hai; Kafka down pe order kabhi at risk nahi, sirf event delayed.

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Direct dual write** | `save()` then `send()` | simple | **broken** — inconsistency window | kabhi nahi (production mein) |
| **Transactional outbox + polling relay** *(ShopFlow)* | row+event ek txn, scheduler publish | sirf app code, no extra infra, atomic | polling latency (~1s), outbox table maintain | default choice |
| **Outbox + Debezium CDC** | WAL tail → Kafka Connect | no polling, near-zero latency, no app publish code | Kafka Connect/Debezium operate karo, schema/offsets | high throughput, platform team hai |
| **2PC / XA (DB+broker)** | distributed transaction | "real" atomicity | slow, locks, broker support poor, fragile | lagभाग kabhi nahi |
| **Listen-to-yourself** | publish to Kafka, apna consumer DB likhe | single write path | ordering/latency, read-after-write issues | special cases |

## Understanding check (khud se, answer dekhe bina)
1. `save()` then `send()` mein exact crash window kaunsa hai, aur us window mein kya kho jaata hai?
2. Relay `markPublished()` se pehle broker ack ka wait kyu karta hai?
3. `outbox_unpublished` badh raha hai par `POST /orders` 201 de raha — kya toota, kya nahi?
4. At-least-once ka seedha natija consumer pe kya requirement banata hai?
5. `@Transactional` private same-class method pe kyu fail hota, aur ShopFlow ne iski jagah kya use kiya?
6. 3 pods pe relay chal rahi hai — same row 3 baar publish hone se kya rokta, aur pods block kyu nahi karte?

## Interview mein ye bolo
*"Dual-write problem: DB aur Kafka ek saath atomically update nahi ho sakte. ShopFlow order aur uska event ek hi Postgres transaction mein `outbox_event` table mein likhta hai (TransactionTemplate se, @Transactional ke proxy trap se bachne ke liye). `OutboxRelay` har second unpublished rows padh ke Kafka pe bhejta hai, broker ack ka wait karke mark karta hai — delivery at-least-once hai, isliye consumers `eventId` pe dedupe karte hain. Relay har pod pe chalti, par `findUnpublished` `FOR UPDATE SKIP LOCKED` use karta (pessimistic lock + timeout -2), to har pod disjoint rows leta — multi-pod pe bhi double-publish nahi. Kafka down ho to order phir bhi save hota hai aur event backlog mein ruk ke recovery pe chala jaata hai; ek gauge us backlog pe alert karta hai."*

> Next: [`../02-saga-cancel-refund/`](../02-saga-cancel-refund/) — ye events aage saga banate hain.
</content>
