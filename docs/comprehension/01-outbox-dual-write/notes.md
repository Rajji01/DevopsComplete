# 01 · Transactional outbox — concept notes (kya / kyu / kaise)

> Isi topic ka comprehension round: [`comprehension.md`](comprehension.md). Code: `shopflow/shopflow-outbox/` + `order-service/…/OrderService.java#saveWithEvent`.

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

## Interview mein ye bolo
*"Dual-write problem: DB aur Kafka ek saath atomically update nahi ho sakte. ShopFlow order aur uska event ek hi Postgres transaction mein `outbox_event` table mein likhta hai (TransactionTemplate se, @Transactional ke proxy trap se bachne ke liye). `OutboxRelay` har second unpublished rows padh ke Kafka pe bhejta hai, broker ack ka wait karke mark karta hai — delivery at-least-once hai, isliye consumers `eventId` pe dedupe karte hain. Kafka down ho to order phir bhi save hota hai aur event backlog mein ruk ke recovery pe chala jaata hai; ek gauge us backlog pe alert karta hai."*

> Next: [`../02-saga-cancel-refund/`](../02-saga-cancel-refund/) — ye events aage saga banate hain.
</content>
