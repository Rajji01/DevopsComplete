# 10 — Kafka & Event-Driven Architecture (Interview Notes)

> Source of truth in this repo (every class / key below exists):
> - `shopflow/order-service/src/main/java/com/shopflow/order/outbox/` — `OutboxEvent`, `OutboxRepository`, `OutboxRelay`, `OrderEvent`
> - `shopflow/order-service/src/main/java/com/shopflow/order/order/OrderService.java` — `saveWithEvent(...)` (order row + outbox row in one `TransactionTemplate`)
> - `shopflow/order-service/src/main/resources/db/migration/V2__outbox.sql`, `application.yml` (`spring.kafka.producer.*`, `outbox.relay.delay`)
> - `shopflow/notification-service/` — `OrderEventListener`, `ProcessedEvent`, `KafkaErrorConfig`, `V1__processed_event.sql`, `application.yml` (consumer settings)
> - Tests: `OrderServiceApplicationTests.publishesOrderEventThroughTheOutbox` (`@EmbeddedKafka`), `NotificationServiceApplicationTests` (duplicate delivery, poison pill → DLT)
> - Infra: `docker-compose.yml` (`apache/kafka:4.1.0`, KRaft), `k8s/base/kafka/statefulset.yaml`, `k8s/base/network-policies.yaml`, `monitoring/alert-rules.yml` (`OutboxBacklogGrowing`, `KafkaConsumerLagHigh`), `infra/terraform/aws/msk.tf`
>
> Honest scope: Kafka is used for **one topic** (`orders.events`) with **one consumer group** (`notification-service`). The order → inventory reservation is still **synchronous REST** (orchestration). Say that clearly; then explain why.

---

## 0. Cheat table (revise 10 minutes before)

| Item | ShopFlow value | Where |
|---|---|---|
| Topics | `orders.events` (`OrderEvent.TOPIC`; consumers: groups `notification-service`, `payment-service`) and `payments.events` (`PaymentEvent.TOPIC`; consumer: group `order-service`), DLTs `orders.events.DLT` / `payments.events.DLT` | `OrderEvent.java`, `PaymentEvent.java`, `KafkaErrorConfig` (×3) |
| Key | `aggregateId` = `orderRef` → all events of one order on one partition, in order | `OutboxRelay.publishPending` |
| Value | JSON string of `OrderEvent(eventId, type, orderRef, sku, quantity, status, occurredAt)` / `PaymentEvent(eventId, type, orderRef, amount, failureReason, occurredAt)` | `OutboxPublisher.toJson` (shared module) |
| Event types | `OrderConfirmed`, `OrderPaid`, `OrderRejected`, `OrderFailed`, `OrderCancelled` (`"Order" + Capitalize(status)`); `PaymentCaptured`, `PaymentFailed` | `OrderEvent.from`, `PaymentEvent.captured/failed` |
| Producer | `acks: all`, `enable.idempotence: true`, `KafkaTemplate<String,String>`, `send(...).get(5s)` | `application.yml`, `OutboxRelay` |
| Outbox table | `outbox_event(id, topic, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)` + index `(published_at, created_at)` — one per publishing service | order `V2__outbox.sql` + `V4__outbox_topic.sql`, payment `V1__payment.sql` |
| Relay | shared module `shopflow-outbox`: `@Scheduled(fixedDelayString = "${outbox.relay.delay:1s}")`, batch 100, `@Transactional`, `FOR UPDATE SKIP LOCKED`, `send(event.getTopic(), aggregateId, payload)` | `OutboxRelay`, `OutboxRepository`, `OutboxPublisher` |
| Cleanup | `@Scheduled(cron = "${outbox.cleanup.cron:0 30 3 * * *}")` (03:30 UTC daily) → `deletePublishedBefore(now - ${outbox.cleanup.retention:7d})`; unpublished rows never deleted | `OutboxRelay.cleanup`, `OutboxRepository` |
| Saga step 2/3 | payment-service `OrderConfirmedListener` (group `payment-service`): `OrderConfirmed` → charge → `payment` row (PK = eventId) + `PaymentCaptured`/`PaymentFailed` outbox in one tx; order-service `PaymentEventListener` (group `order-service`): `processed_event` dedupe → PAID or release + CANCELLED | `OrderConfirmedListener`, `PaymentEventListener`, `OrderService.onPayment*` |
| Saga timeout | `OrderReconciler` `@Scheduled(fixedDelay 60s)` → `reconcileFailedOrders(grace 2m)`: FAILED or PENDING → idempotent `release` → CANCELLED + `OrderCancelled` event | `OrderReconciler`, `OrderService` |
| MSK client auth | profile `aws` (`application-aws.yml`): `SASL_SSL` + `AWS_MSK_IAM` + `IAMLoginModule` + `IAMClientCallbackHandler`; runtime dep `aws-msk-iam-auth 2.3.9`; `SPRING_PROFILES_ACTIVE=aws` in the prod overlay | order-, notification- and payment-service, `overlays/prod/kustomization.yaml` |
| Backlog metric | gauge `outbox.unpublished` → Prometheus `outbox_unpublished` | `OutboxRelay` constructor |
| Consumers | groups `notification-service`, `payment-service` (both on `orders.events`, independent offsets) and `order-service` (on `payments.events`); all `enable-auto-commit: false`, `auto-offset-reset: earliest`, `isolation.level: read_committed` | notification / payment / order `application.yml` |
| Idempotency | inbox table `processed_event(event_id PK)` checked + inserted in the same `@Transactional` as the side effect (notification-service, order-service `V5`); **PK = eventId** variant in payment-service (`payment.event_id`); `ProcessedEventCleanup` daily, 7d | `OrderEventListener`, `PaymentEventListener`, `Payment`, `ProcessedEventCleanup` |
| Event-type filter | consumers act only on the types they own: payment-service returns early unless `type == "OrderConfirmed"`; order-service switches on `PaymentCaptured` / `PaymentFailed` and logs unknown types | `OrderConfirmedListener`, `PaymentEventListener` |
| Errors | `DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOff(500ms, ×2, max 10s))` in all three consumers | `KafkaErrorConfig` (notification, payment, order) |
| Tracing | `spring.kafka.template.observation-enabled` / `listener.observation-enabled: true` → trace context in headers | all Kafka `application.yml`s |
| Broker (local) | `apache/kafka:4.1.0`, KRaft (`KAFKA_PROCESS_ROLES: broker,controller`), `KAFKA_NUM_PARTITIONS: 3`, RF 1 | compose + `k8s/base/kafka` |
| Broker (prod) | Amazon MSK: 3 brokers / 3 AZs, `default.replication.factor=3`, `min.insync.replicas=2`, `num.partitions=6`, `auto.create.topics.enable=false`, IAM auth, TLS | `infra/terraform/aws/msk.tf` |
| Alerts | `OutboxBacklogGrowing` (`outbox_unpublished > 100` for 5m, page), `KafkaConsumerLagHigh` (lag > 1000 for 10m, ticket), `PaymentFailureRateHigh` (`payments_total{status="FAILED"}` ratio > 0.5 for 10m, page) | `monitoring/alert-rules.yml` |
| Tests | `@EmbeddedKafka(partitions = 1, topics = ...)`, `${spring.embedded.kafka.brokers}` in `application-test.yml`, relay delay 100ms | order, notification and payment test classes |

---

## 1. Kafka fundamentals (the vocabulary you must own)

```
Cluster = N brokers.  Topic "orders.events", 3 partitions, replication factor 3

 broker-1            broker-2            broker-3
 ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 │ p0 (leader)  │    │ p0 (follower)│    │ p0 (follower)│   ISR(p0) = {1,2,3}
 │ p1 (follower)│    │ p1 (leader)  │    │ p1 (follower)│
 │ p2 (follower)│    │ p2 (follower)│    │ p2 (leader)  │
 └──────────────┘    └──────────────┘    └──────────────┘
 Producer writes to the LEADER of a partition; followers replicate; consumers read from leader (or a follower replica).

 partition p1:  offset 0 ──▶ 1 ──▶ 2 ──▶ 3 ──▶ 4 ──▶ 5   (append-only log, ordered, immutable)
                                 ▲ committed offset of group "notification-service" = 3 (next to read)
                                                  ▲ log end offset = 6   → lag = 6 - 3 = 3
```

| Term | One-line definition | ShopFlow |
|---|---|---|
| **Broker** | A Kafka server; stores partitions, serves producers/consumers | 1 broker locally, 3 on MSK |
| **Topic** | Named stream, split into partitions | `orders.events` |
| **Partition** | Ordered, append-only log; **unit of ordering and parallelism** | 3 locally (`KAFKA_NUM_PARTITIONS`), 6 on MSK |
| **Offset** | Position of a record in a partition (per partition, starts at 0) | consumer commits after processing |
| **Replication factor** | Copies of each partition on different brokers | 1 locally (dev only!), 3 on MSK |
| **Leader / follower** | Leader serves reads/writes; followers replicate | controller elects a new leader on failure |
| **ISR** | In-Sync Replicas: followers caught up with the leader | `min.insync.replicas=2` on MSK |
| **Consumer group** | Consumers sharing a topic; each partition → exactly one consumer in the group | `groupId = "notification-service"` |
| **Rebalance** | Partitions reassigned when a consumer joins/leaves/dies | HPA scaling pods triggers it |
| **Retention** | Records kept by time/size, **not deleted when consumed** | `log.retention.hours=168` (7 days) on MSK |
| **Compaction** | Keep only the latest record per key (`cleanup.policy=compact`) | not used; `__consumer_offsets` is compacted |
| **Controller** | Broker role that manages metadata/leader election | KRaft: `KAFKA_PROCESS_ROLES: broker,controller` |

### Producer side

- **Partitioner**: with a key → `murmur2(key) % partitions` (so same key = same partition, as long as partition count does not change). Without a key → sticky partitioner (batches to one partition then switches). ShopFlow always sends a key (`orderRef`).
- **`acks`**: `0` fire-and-forget, `1` leader wrote it, `all` (= `-1`) leader **and every ISR** wrote it. `acks=all` + `min.insync.replicas=2` + RF 3 = survives one broker loss without losing an acknowledged record. ShopFlow: `acks: all`.
- **Idempotent producer** (`enable.idempotence=true`): broker assigns a producer id + sequence numbers per partition and **drops duplicates caused by producer retries** (network blip after the broker wrote the batch). It also forces `acks=all`, `retries>0`, `max.in.flight<=5` while keeping order. Default `true` since Kafka 3.0 — ShopFlow sets it explicitly to make the intent visible.
- **Batching**: `linger.ms`, `batch.size`, `compression.type` (lz4/zstd) — throughput knobs. Not tuned in ShopFlow (volume is tiny).
- **Send is async**: `kafkaTemplate.send()` returns a `CompletableFuture`. `OutboxRelay` calls `.get(5, SECONDS)` because it must know the broker acknowledged **before** marking the outbox row published.

### Consumer side

- **Poll loop**: `poll()` returns a batch; `max.poll.records` (default 500) and `max.poll.interval.ms` (default 5 min) — if processing a batch takes longer than that, the consumer is kicked out of the group (rebalance). Heartbeats run on a separate thread (`session.timeout.ms`, `heartbeat.interval.ms`).
- **Offset commit strategies**:

| Strategy | How | Semantics |
|---|---|---|
| Auto-commit (`enable.auto.commit=true`, every 5s) | the client commits the offsets of the **previous** `poll()` on the next `poll()` (if 5s passed), regardless of what your code did with them | roughly at-least-once in a plain synchronous loop, but **lossy as soon as processing is handed to another thread** (records committed before they were processed) and imprecise on crash (up to 5s re-processed). Never for side effects |
| Manual after processing (`enable.auto.commit=false`) | Spring container commits after the listener returns (`AckMode.BATCH` default, or `RECORD`) | **at-least-once**: crash after processing but before commit → redelivery → consumer must be idempotent |
| Manual `Acknowledgment.acknowledge()` (`AckMode.MANUAL`) | your code decides when | same at-least-once, more control |
| Commit before processing | — | at-most-once (acceptable for metrics/logs, never for emails/money) |

ShopFlow: `enable-auto-commit: false` → Spring Kafka commits after `onOrderEvent` returns. The `processed_event` table turns at-least-once into **effectively once** for the side effect.

- **`auto.offset.reset`**: where a **new** group starts when it has no committed offset: `earliest` (replay history) or `latest` (only new). ShopFlow: `earliest` — a freshly deployed notification-service processes the events that were produced before it existed (the comment in `application.yml` says exactly this). Note: it only matters for a group with **no** committed offsets; an existing group always resumes from its commit.
- **`isolation.level: read_committed`**: skip records from aborted Kafka transactions. ShopFlow's producer does not use Kafka transactions, so this is harmless hygiene — say "defensive default so a future transactional producer is handled correctly".

### Rebalancing

- **Eager (range/round-robin)**: *stop-the-world* — every consumer gives up all partitions, then all are reassigned. Short pause on every scale event.
- **Cooperative sticky** (`CooperativeStickyAssignor`): only the partitions that must move are revoked; others keep processing. Fewer duplicates, less pause. **Not the default**: since 3.0 the client default is `[RangeAssignor, CooperativeStickyAssignor]`, which still *uses* Range (eager) until you remove Range from the list — ShopFlow runs the eager default.
- **Static membership** (`group.instance.id`): a pod restart within `session.timeout.ms` does **not** trigger a rebalance — useful with Kubernetes rolling updates / StatefulSets.
- **Rebalance storm**: consumers repeatedly joining/leaving — typically `max.poll.interval.ms` exceeded (slow processing), flapping pods (OOMKilled, failing liveness), or HPA thrash. See Scenario 4.

### KRaft vs ZooKeeper

| | ZooKeeper mode (legacy) | KRaft (Kafka Raft) |
|---|---|---|
| Metadata store | external ZooKeeper ensemble | internal quorum of **controller** nodes (Raft), metadata is itself a Kafka log |
| Ops | two systems to run, secure, monitor | one system |
| Scale | partition count limited (~200k) by ZK | millions of partitions, faster controller failover |
| Status | removed in **Kafka 4.0** | the only mode in Kafka 4.x |

ShopFlow runs `apache/kafka:4.1.0` with `KAFKA_PROCESS_ROLES: broker,controller` (combined mode) and `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` — a single node that is both broker and controller. Port **9092** = client listener (PLAINTEXT), **9093** = controller listener (the `kafka-ingress` NetworkPolicy allows 9093 only from kafka pods: "controller quorum traffic"). Production (MSK) runs 3 brokers with separate controller handling managed by AWS.

### Delivery semantics & exactly-once

| Level | How | Duplicates? Loss? |
|---|---|---|
| At-most-once | commit offset before processing | loss possible, no duplicates |
| At-least-once | process, then commit (ShopFlow) | duplicates possible, no loss |
| Exactly-once (Kafka → Kafka) | idempotent producer + **Kafka transactions** (`transactional.id`, `sendOffsetsToTransaction`, consumers `read_committed`) — Kafka Streams `processing.guarantee=exactly_once_v2` | within Kafka only |
| "Exactly-once" end-to-end with a DB / email / HTTP call | **impossible in general** → at-least-once + **idempotent consumer** (dedupe table or naturally idempotent operation) | this is what ShopFlow does |

Say it this way: *"Kafka transactions give exactly-once between Kafka topics. The moment the consumer writes to Postgres or sends an email, you need idempotency — so I store the `eventId` in `processed_event` in the same DB transaction as the side effect."*

### Ordering

- Guaranteed **only within a partition**. Therefore: pick a key such that everything that must be ordered shares the key. ShopFlow: `orderRef` → `OrderConfirmed` then `OrderCancelled` of the same order always arrive in order at the same consumer.
- Things that break ordering: changing the partition count (key → partition mapping changes), producer retries without idempotence (`max.in.flight > 1`), consumer-side thread pools that process records of one partition concurrently, and **retry topics** (a failed record is retried later while newer records of the same key flow on).
- `OutboxRelay` protects producer ordering in two ways: rows are read `order by createdAt`, and on the first failed send it `return`s (stops the batch) instead of skipping ahead — "keep ordering: stop at the first failure, next run retries from here".

### Headers, schema & versioning

- **Headers**: key/value metadata per record. Spring's observation support puts the **W3C `traceparent`** there (`observation-enabled: true`) so notification-service spans join the order-service trace. `DeadLetterPublishingRecoverer` adds headers to DLT records: original topic/partition/offset, exception class, message and stack trace — this is how you debug a DLT record.
- **Schema evolution, the two schools**:

| | Schema Registry (Avro / Protobuf / JSON Schema) | JSON + tolerant reader (ShopFlow) |
|---|---|---|
| Contract | explicit schema stored in Confluent / Apicurio / AWS Glue Schema Registry; producer registers, consumer fetches by schema id in the record | the record class in each service (`OrderEvent` is **copied** into notification-service — "Copy of order-service's event contract") |
| Compatibility | enforced at produce time: BACKWARD / FORWARD / FULL rules reject breaking schemas | by convention: only **add** fields; consumers use `@JsonIgnoreProperties(ignoreUnknown = true)` |
| Payload size | compact binary | verbose text |
| Tooling | Avro needs codegen / generic records, `KafkaAvroSerializer` | plain Jackson, `StringSerializer` |
| Good for | many teams, many consumers, long-lived data | small systems, few consumers, speed of development |

ShopFlow's choice is proven by a test: `NotificationServiceApplicationTests.event(...)` includes `"futureField":"ignored"` and the consumer still processes it. Rules to state: additive changes only; never rename/retype; when you must break, add an explicit `version` field or a new event type (`OrderConfirmedV2`) / new topic and run both until consumers migrate.

> Tip: Interviewer "exactly-once possible hai?" pooche toh seedha "Kafka ke andar haan, DB/email ke saath nahi — isliye idempotent consumer" bolo. Yeh ek line senior-level answer hai.

### Spring Kafka configuration reference (what ShopFlow sets, what you should still know)

| Property / API | ShopFlow | Why / what else to know |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`; tests `${spring.embedded.kafka.brokers}` | one env var per environment; prod: MSK bootstrap string from the `shopflow-endpoints` ConfigMap |
| `spring.kafka.producer.acks` | `all` | `all` is already the client default since Kafka 3.0 (idempotence on); setting it makes the "no acknowledged write lost" intent explicit and survives someone disabling idempotence |
| `producer.properties.enable.idempotence` | `true` | de-dupes producer retries; implies `acks=all`, `max.in.flight.requests.per.connection ≤ 5` |
| `producer.key/value-serializer` | default `StringSerializer` (JSON produced by Jackson in `OrderService.toJson`) | `JsonSerializer` adds type headers; Avro needs `KafkaAvroSerializer` + registry URL |
| `spring.kafka.template.observation-enabled` | `true` | producer span + `traceparent` header |
| `spring.kafka.consumer.group-id` | set on the annotation: `@KafkaListener(groupId = "notification-service")` | one group per *purpose*; a second service (analytics) uses its own group and gets every record too |
| `consumer.auto-offset-reset` | `earliest` | only for groups with no committed offset |
| `consumer.enable-auto-commit` | `false` | the listener container commits (`AckMode.BATCH` default) after the listener returns |
| `consumer.properties.isolation.level` | `read_committed` | ignore aborted transactional records |
| `spring.kafka.listener.observation-enabled` | `true` | consumer span joins the producer's trace |
| `spring.kafka.listener.ack-mode` | default (`BATCH`) | `RECORD` commits after each record (more commits, smaller redelivery window); `MANUAL`/`MANUAL_IMMEDIATE` with an `Acknowledgment` parameter |
| `spring.kafka.listener.concurrency` | default 1 thread per `@KafkaListener` | up to the number of partitions per pod; with 3 partitions and `concurrency: 3` one pod can drain them all |
| `consumer.max-poll-records`, `properties.max.poll.interval.ms` | defaults (500, 300000) | lower records / raise interval if a batch takes long, or the consumer is kicked out of the group |
| `DefaultErrorHandler` bean | `ExponentialBackOff(500, 2.0)` + `setMaxElapsedTime(10_000)` + `DeadLetterPublishingRecoverer(template)` | Spring Boot wires the bean into the listener container factory automatically; `addNotRetryableExceptions(...)` to skip retries for permanent failures |
| `ErrorHandlingDeserializer` | not needed (String payload parsed in code) | mandatory wrapper when using `JsonDeserializer` so bad bytes do not loop forever |
| `@RetryableTopic` | not used | non-blocking retries via `-retry-<n>` topics + `-dlt`; breaks per-key ordering |
| `KafkaAdmin` + `NewTopic` beans | not used (topics auto-created locally; on MSK `auto.create.topics.enable=false`, so an admin/Job creates them — Terraform's `aws_msk_*` has no topic resource) | lets the app declare partitions/RF/retention at startup — also useful in tests |
| Kafka transactions (`transaction-id-prefix`, `KafkaTransactionManager`, `executeInTransaction`) | not used — the outbox makes them unnecessary for the DB→Kafka hop | needed for consume-transform-produce exactly-once between topics |
| `spring.kafka.security.protocol` / `sasl.*` | PLAINTEXT locally; **profile `aws`** (`application-aws.yml` in order- and notification-service, activated by `SPRING_PROFILES_ACTIVE=aws` in the prod overlay) sets the four MSK IAM properties | MSK IAM: `SASL_SSL`, `sasl.mechanism=AWS_MSK_IAM`, `sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;`, `sasl.client.callback.handler.class=...IAMClientCallbackHandler` + the `aws-msk-iam-auth` jar |

### Topic sizing & configuration (what to say when asked "how many partitions?")

| Decision | Rule of thumb | ShopFlow |
|---|---|---|
| Partitions | `max(target throughput / per-consumer throughput, number of consumers you want busy)`, rounded up with headroom; every partition costs file handles, memory and rebalance time | 3 (local default `KAFKA_NUM_PARTITIONS`), 6 on MSK; notification HPA max 6 in the base matches MSK, prod max 10 leaves 4 idle |
| Replication factor | 3 in prod (survive one broker + one in maintenance); 1 only for dev | 1 local, 3 MSK |
| `min.insync.replicas` | RF − 1 | 2 on MSK |
| Retention | long enough to replay after an outage or to backfill a new consumer; cost = bytes × RF | 7 days (`log.retention.hours=168`) on MSK |
| `cleanup.policy` | `delete` for event streams, `compact` for state/changelog | `delete` |
| Key | the aggregate id you need ordering for | `orderRef` |
| Topic naming | `<domain>.<kind>` or `<team>.<domain>.<event>`, versions in the name only for breaking changes | `orders.events`, DLT `orders.events.DLT` |
| Message size | keep well under `message.max.bytes` (1 MB default); large payloads → claim-check (S3 pointer) | a few hundred bytes of JSON |

---

## 2. The dual-write problem and the transactional outbox (as implemented)

### The bug the outbox fixes

```java
// WRONG (dual write): two systems, no shared transaction
orderRepository.save(order);                 // Postgres commit OK
kafkaTemplate.send("orders.events", event);  // crash / broker down here → order exists, nobody is notified
```
Reverse the order and you get the opposite (event sent, DB insert fails → notification for an order that does not exist). Wrapping both in `@Transactional` does **not** help — the Kafka send is not part of the JDBC transaction (and the send may succeed while the DB commit then fails).

### The ShopFlow implementation, file by file

```
POST /api/v1/orders
   │
   ▼
OrderService.placeOrder()
   ├─ orderRepository.save(Order.pending(...))         (own short tx — no event yet)
   ├─ inventoryClient.reserve(...)                     (HTTP, retry + breaker, OUTSIDE any tx)
   └─ saveWithEvent(order) ──────────────────────────────────────────────────┐
                                                                             ▼
        transactionTemplate.execute(status -> {                     ONE local Postgres tx
            Order persisted = orderRepository.save(order);          UPDATE orders ... (+ @Version check)
            OrderEvent event = OrderEvent.from(persisted);          eventId = UUID, type = "OrderConfirmed"
            outboxRepository.save(new OutboxEvent("Order",          INSERT outbox_event (published_at NULL)
                   persisted.getOrderRef(), event.type(), toJson(event)));
            return persisted;
        });                                                          COMMIT → both rows or neither
   │
   ▼  (every 1s, any order-service pod)
OutboxRelay.publishPending()  @Scheduled @Transactional
   ├─ findUnpublished(PageRequest.of(0, 100))   SELECT ... WHERE published_at IS NULL ORDER BY created_at
   │                                            FOR UPDATE SKIP LOCKED   ← PESSIMISTIC_WRITE + lock.timeout -2
   ├─ for each row: kafkaTemplate.send(TOPIC, aggregateId /*key*/, payload).get(5s)   acks=all
   │                event.markPublished()        (dirty-checked, flushed at commit)
   │                on exception → log WARN, return (retry next run; ordering kept)
   └─ COMMIT → published_at set, row locks released
   │
   ▼  Kafka topic orders.events (key = orderRef → partition = murmur2(orderRef) % 3)
   │
   ▼  notification-service  @KafkaListener(groupId="notification-service")  @Transactional
OrderEventListener.onOrderEvent(String payload)
   ├─ objectMapper.readValue(payload, OrderEvent.class)         (bad JSON → exception → error handler)
   ├─ processedEvents.existsById(eventId) ? count duplicate, return
   ├─ notify(event)   → log "EMAIL -> customer: Your order ... is confirmed"  (stand-in for SES/SNS/FCM)
   ├─ processedEvents.save(new ProcessedEvent(eventId, orderRef))   same tx as the side effect
   └─ return → container commits the Kafka offset
   │
   ▼  payment-service  @KafkaListener(topics="orders.events", groupId="payment-service")  @Transactional   (same records, own offsets)
OrderConfirmedListener.onOrderEvent(String payload)
   ├─ type != "OrderConfirmed" → return                        (OrderCancelled etc. are not ours; offset still committed)
   ├─ payments.existsById(event.eventId()) → count payments.duplicates, return   (PK = eventId = the inbox)
   ├─ amount = PriceList.priceOf(sku) × quantity ; gateway.charge(orderRef, amount)   (FakePaymentGateway: declined if > payment.card-limit 100000)
   ├─ payments.save(new Payment(eventId, orderRef, amount, CAPTURED|FAILED, reason))
   ├─ outbox.publish("payments.events", "Payment", orderRef, "PaymentCaptured"|"PaymentFailed", event)   INSERT outbox_event, same tx
   └─ COMMIT → both rows or neither ; counter payments_total{status}
   │
   ▼  OutboxRelay (same shared class, payment-service pod) → topic payments.events (key = orderRef)
   │
   ▼  order-service  @KafkaListener(topics="payments.events", groupId="order-service")  @Transactional
PaymentEventListener.onPaymentEvent(String payload)
   ├─ processedEvents.existsById(eventId) → skip                (processed_event, V5__processed_event.sql)
   ├─ "PaymentCaptured" → orderService.onPaymentCaptured(orderRef) → if CONFIRMED: markPaid() + saveWithEvent (OrderPaid)
   ├─ "PaymentFailed"   → orderService.onPaymentFailed(orderRef, reason) → if CONFIRMED: inventoryClient.release + cancel("payment failed: …") + saveWithEvent (OrderCancelled)
   ├─ default → log.warn unknown type
   └─ processedEvents.save(new ProcessedEvent(eventId)) → COMMIT → offset committed
```

Details worth saying out loud:

1. **Why `TransactionTemplate` and not `@Transactional saveWithEvent()`?** `saveWithEvent` is a `private` method called from `placeOrder` **inside the same class**. `@Transactional` is proxy-based: `this.saveWithEvent()` bypasses the proxy, so the annotation would be silently ignored and the two inserts would commit separately — the dual write would be back. `TransactionTemplate` is programmatic, works on self-invocation, and keeps the transaction **short** (two inserts, no HTTP). The class Javadoc states both reasons.
2. **Why no `@Transactional` on `placeOrder`?** It contains the remote `reserve()` call. Holding a Hikari connection (pool of 10) for up to ~6.6s of retries would starve the service (see note 01, §4.3).
3. **Which events exist?** `OrderEvent.from(order)` derives the type from the status: CONFIRMED → `OrderConfirmed`, REJECTED → `OrderRejected`, FAILED → `OrderFailed` (emitted in the `catch` before the 503 is thrown), CANCELLED → `OrderCancelled`. The PENDING save emits nothing — the business outcome is not known yet.
4. **`eventId` vs `orderRef`**: `orderRef` is the **key** (ordering/partitioning + business correlation); `eventId` is a fresh UUID per event (**dedupe** key on the consumer). One order produces several events, so deduping on `orderRef` would wrongly drop `OrderCancelled` after `OrderConfirmed`.
5. **At-least-once by design**: if the pod dies between `send().get()` and the commit that writes `published_at`, the row is still unpublished → the next run sends it again → the consumer sees the same `eventId` → skipped. The relay's Javadoc says this and points to notification-service.
6. **`SKIP LOCKED`** (`OutboxRepository`): `@Lock(PESSIMISTIC_WRITE)` renders `SELECT ... FOR UPDATE`; the JPA hint `jakarta.persistence.lock.timeout = -2` makes Hibernate render **`SKIP LOCKED`** on PostgreSQL (Hibernate's magic values: `-2` = `SKIP LOCKED`, `-1` = wait forever, `0` = `NOWAIT`). Effect: with 3 order-service pods, each relay run grabs a **different** set of unpublished rows instead of blocking on, or double-publishing, the rows another pod is sending. Rows already locked by another pod are simply not returned.
7. **`.get(5, SECONDS)`** on the send: synchronous wait for the `acks=all` acknowledgement. Without it `markPublished()` could run for a message the broker never got. Trade-off: the relay is sequential (≤100 sends per second per pod at 1s delay with slow acks) — fine for this volume; for high volume you would send a batch asynchronously and mark published in the callbacks.
8. **Index** `idx_outbox_pending (published_at, created_at)` matches the query's `WHERE published_at IS NULL ORDER BY created_at`. The SQL comment admits a PostgreSQL **partial index** (`WHERE published_at IS NULL`) would be smaller, but H2 (tests) cannot parse it. Published rows are kept only for debugging and are deleted by the retention job (next paragraph).
8b. **Retention / cleanup** (`OutboxRelay.cleanup`): `@Scheduled(cron = "${outbox.cleanup.cron:0 30 3 * * *}")` — 03:30 UTC daily, off-peak — calls `cleanupPublishedBefore(Instant.now().minus(retention))` with `outbox.cleanup.retention: 7d`, which runs `OutboxRepository.deletePublishedBefore(before)`: `@Modifying @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")`. Unpublished rows are never touched, so an outage backlog can never be lost to housekeeping. Why 7 days: it matches the broker's `log.retention.hours=168`, so for as long as Kafka can still redeliver an event the DB copy is there to compare against. Why a single `DELETE` rather than partitions: ShopFlow's volume is small; at millions of rows per day you would partition `outbox_event` by day and `DROP` partitions instead (a bulk delete bloats the table and needs autovacuum to catch up). Test: `OutboxCleanupTest.deletesOnlyPublishedEventsOlderThanRetention` (one published + one pending row, cut-off in the future → only the published row is gone).
9. **Backlog gauge**: `meterRegistry.gauge("outbox.unpublished", outboxRepository, OutboxRepository::countByPublishedAtIsNull)` → `outbox_unpublished` in Prometheus → alert `OutboxBacklogGrowing` (`> 100 for 5m`, page). This is the **one metric that tells you Kafka is down** from the producer's point of view, even though every HTTP request still returns 201.
10. **Test**: `publishesOrderEventThroughTheOutbox` places an order via MockMvc, then a raw `KafkaConsumer` built with `KafkaTestUtils.consumerProps(...)` + `AUTO_OFFSET_RESET earliest` reads `orders.events` from the `@EmbeddedKafka` broker and Awaitility waits (≤10s) for a record whose **key equals the `orderRef`** and whose value contains `"type":"OrderConfirmed"` and `"quantity":3`. Relay delay is `100ms` in `application-test.yml`.

### Polling publisher vs CDC (Debezium)

| | Polling publisher (ShopFlow) | CDC / Debezium Outbox Event Router |
|---|---|---|
| Mechanism | `@Scheduled` query every N ms | reads the Postgres WAL (logical replication slot) and streams inserts to Kafka |
| Latency | ≥ poll interval (1s here) | ms |
| DB load | one indexed query per second per pod | replication stream; no polling |
| Extra infra | none | Kafka Connect cluster + Debezium connector, replication slot management (a stuck slot fills the disk!) |
| Ordering | by `created_at` + stop-on-failure | WAL order |
| Mark published | `UPDATE published_at` (or delete) | table can be insert-only and purged |
| When | small/medium systems, few events | high volume, many aggregates, multiple outbox tables |

### Consumer side: inbox / idempotent consumer

`processed_event(event_id uuid primary key, order_ref, processed_at)`:
- check `existsById` → skip (and count `notifications.duplicates`),
- otherwise do the side effect **and** insert the row **in the same `@Transactional`**: if the insert fails, the side effect's DB work rolls back; if two pods race on the same `eventId` (possible after a rebalance), the second insert hits the PK → exception → the record is retried, finds the row, and is skipped.
- Honest limit: the "email" here is a log line. With a real SES call, the email could be sent and then the DB commit fail → the email goes twice on redelivery. Mitigation: make the provider call idempotent (SES/SNS message dedup ids) or move the send **after** commit with an outbox of its own.
- **Variant without a separate table (payment-service):** the business row *is* the inbox — `payment.event_id` is the primary key and equals the `OrderConfirmed` `eventId`. One table, one constraint, and the duplicate check is `existsById`. It works because exactly one payment per triggering event is the business rule anyway (`order_ref` is unique too). The trade-off: it only dedupes events that *create* a row; a consumer that reacts to several event types for the same aggregate (order-service on `PaymentCaptured` and `PaymentFailed`) needs the general `processed_event` table, which is why order-service got one (`V5`). Also, a producer that re-emits the same business fact with a *new* `eventId` would be charged twice — the PK protects against redelivery, not against upstream re-emission (see [12](12-hard-interview-questions.md) D11).
- **Retention:** `ProcessedEventCleanup` (notification- and order-service, `@Scheduled(cron = "${processed-events.cleanup-cron:0 50 3 * * *}")`, `processed-events.retention: 7d`) deletes rows older than the topic's retention — a redelivery older than that cannot happen anymore, so the table no longer grows forever.
- **Event-type filtering:** every consumer reads the whole topic but acts only on its own types — payment-service returns before any DB lookup unless `type == "OrderConfirmed"` (`ignoresOtherOrderEvents` test), order-service `switch`es on the type and logs unknown ones. Ignored records still commit their offset; a thin topic-per-type design would avoid the reads but lose per-order ordering across types.

Test: `processesEachEventExactlyOnceEvenWhenDeliveredTwice` sends the same JSON twice (same `eventId`), waits for `notifications.duplicates ≥ 1`, and asserts `notifications.sent{type=OrderConfirmed} == 1`.

### Poison pills, retries and the DLT (as configured)

```java
// KafkaErrorConfig
var backOff = new ExponentialBackOff(500L, 2.0);   // 0.5s, 1s, 2s, 4s, ...
backOff.setMaxElapsedTime(10_000L);                // ... until ~10s have passed in total
return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
```
- A record that throws is **re-polled and retried in place** (the consumer seeks back to that offset, so the partition is blocked during the retries — that is why the window is short). After the back-off budget is exhausted the recoverer publishes it to **`orders.events.DLT`** (default naming: `<topic>.DLT`, same partition number when the DLT has at least as many partitions) with exception headers, the offset is committed, and the partition moves on.
- Why "bad JSON" lands in the DLT here: the listener receives a `String` (`StringDeserializer`) and calls `objectMapper.readValue` itself, so a parse error is an ordinary listener exception → retried → DLT. If you used `JsonDeserializer` directly, a deserialization failure happens **before** the listener and loops forever unless you wrap it in `ErrorHandlingDeserializer` — a classic interview trap.
- Transient vs permanent: a DB outage longer than 10s also sends good records to the DLT. Improvements: longer backoff for `TransientDataAccessException`, `addNotRetryableExceptions(JsonProcessingException.class)` to skip straight to the DLT for malformed payloads, or **retry topics** (`@RetryableTopic`: `orders.events-retry-1000`, `-retry-2000`, ..., then `-dlt`) which free the main partition at the cost of per-key ordering.
- Someone must **watch the DLT**: a consumer-lag or message-count alert on `orders.events.DLT`, a small admin tool to inspect headers and replay (re-produce to the main topic after a fix).
- Test: `poisonPillGoesToDeadLetterTopicWithoutBlockingOthers` sends `"this is not json"` then a valid event and asserts the valid one is processed (≤30s because the retries run first).

### Consumer lag, scaling and backpressure

- **Lag** = log-end-offset − committed-offset per partition. It is *the* consumer health metric. Sources: broker-side `kafka-consumer-groups --describe`, Burrow, **kafka_exporter**, or client-side Micrometer (`kafka.consumer.fetch.manager.records.lag.max`).
- **Scaling rule**: consumers in a group ≤ partitions; extra pods sit idle. ShopFlow: 3 partitions locally → at most 3 busy notification pods (HPA max 6 is CPU-driven and will not help past 3); MSK uses `num.partitions=6`. More partitions = more parallelism but more open files, more leader elections, slower rebalances, and a **changed key→partition mapping** when you add them to an existing topic.
- **KEDA** (Kafka scaler on lag) is the right autoscaler for consumers, not CPU-based HPA.
- **Backpressure**: Kafka consumers *pull*, so a slow consumer never overwhelms itself; lag just grows and retention is your buffer (7 days on MSK). Tune `max.poll.records` down if a batch takes too long (`max.poll.interval.ms`), or `pause()/resume()` the container.
- ShopFlow alert `KafkaConsumerLagHigh`: `sum by (application) (kafka_consumer_fetch_manager_records_lag_max) > 1000 for 10m` (ticket) — the series Micrometer's Kafka consumer binder actually exports. **Story to tell**: an earlier version of the rule used `spring_kafka_listener_records_lag_max`, a metric that does not exist, so the alert could never fire; `promtool check rules` is syntax-only and did not catch it. It was fixed by checking `curl :8083/actuator/prometheus | grep lag`, and `NotificationServiceApplicationTests` now asserts the `kafka.consumer.fetch.manager.records.lag.max` gauge is registered. Add an `absent()` rule for belt-and-braces. "I validate metric names against the real `/actuator/prometheus` output" is a strong SRE answer.

> Tip: "Lag badh raha hai → pods badhao" galat answer hai agar partitions se zyada pods already hain. Pehle partitions, phir pods, phir processing speed.

---

## 3. Event design & sagas

### Fat vs thin events

| | Thin (notification) event | Fat (event-carried state transfer) |
|---|---|---|
| Payload | `{orderRef, type}` — consumer calls back for details | full snapshot: `{orderRef, sku, quantity, status, occurredAt}` |
| Coupling | consumer depends on producer's API availability | consumer is autonomous |
| Size / schema | tiny, stable | bigger; schema evolution matters |
| ShopFlow | — | **fat**: `OrderEvent` carries everything notification-service needs; it never calls order-service |

### Versioning rules (what ShopFlow does and what it would do)
- Additive only + `@JsonIgnoreProperties(ignoreUnknown = true)` on the consumer copy (done).
- Event **type** in the payload (`type: OrderConfirmed`) so one topic can carry a family of events; a `switch` in `notify()` with a `default` branch handles unknown-but-parseable types.
- Breaking change → new type/version (`OrderConfirmedV2`) or new topic; keep old consumers working; retire later. With a registry: FULL compatibility + CI check.

### Choreography vs orchestration (and where ShopFlow sits)

```
Orchestration (ShopFlow: reserve stock)          Choreography (ShopFlow: payment + notify)
OrderService ──HTTP──▶ inventory reserve         order-service ──OrderConfirmed──▶ orders.events ──▶ notification-service (group notification-service)
    ◀── 201/409/5xx ──                                                                   └──▶ payment-service      (group payment-service)
decides CONFIRMED/REJECTED/FAILED itself         payment-service ──PaymentCaptured|PaymentFailed──▶ payments.events ──▶ order-service (group order-service)
cancel = compensation (release) by the same      order-service reacts: PAID, or release + CANCELLED (compensation triggered by an event)
orchestrator                                     producer does not know who listens (could add: analytics, loyalty, search index)
```

| | Orchestration | Choreography |
|---|---|---|
| Who decides | a central coordinator (`OrderService`) | each service reacts to events |
| Visibility | easy to see the flow and the state machine | flow is implicit; needs tracing |
| Coupling | coordinator knows all participants | participants know only event contracts |
| Failure handling | explicit compensation steps (cancel → release) | each service handles its own compensation events |
| Fits | a business transaction needing an immediate answer (stock yes/no) | fan-out side effects (email, analytics) |

Why ShopFlow mixes both: the customer must know **now** whether stock was reserved → synchronous orchestration with timeouts/retry/breaker. Payment and notifications must not slow down or fail the order → asynchronous events through the outbox; payment-service being down means lag, not failed orders, and order-service has no payment client, timeout or breaker to tune. The price is visibility: the saga is only visible end to end in Tempo (the `traceparent` header travels through both topics), so the state machine stays explicit in `OrderStatus` and both listeners are status-guarded (`Order::isConfirmed`) to make late/duplicate events harmless. A fully event-driven reservation (order PENDING → `OrderPlaced` → inventory consumes → `StockReserved` → order CONFIRMED) is the next step and would remove the "inventory down = orders fail" coupling, at the cost of a PENDING state the UI must poll.

**Saga timeout handling — reconciliation of FAILED orders (as implemented).** A saga needs an answer for "the step neither succeeded nor failed": inventory timed out, or the breaker was open, so the order is FAILED and we do not know whether stock is reserved. `OrderReconciler` (`@Scheduled(fixedDelayString = "${orders.reconcile.delay:60s}")`) calls `OrderService.reconcileFailedOrders(grace)` with `orders.reconcile.grace: 2m`: `findTop100ByStatusInAndUpdatedAtBefore(List.of(FAILED, PENDING), now - grace)` (index `idx_orders_status_updated`) → for each order `inventoryClient.release(orderRef)` → `order.cancel()` → `saveWithEvent` → `OrderCancelled` through the outbox. Properties that make it safe: `release` is **idempotent** (unknown or already-released `orderRef` → no-op, RESERVED → stock back), so the worst case is a harmless no-op; the batch **stops on the first `RestClientException`/`CallNotPermittedException`** (inventory still down → try again in 60s, do not amplify the outage); the grace period gives the customer time to retry/cancel before the system decides. Design choice: compensate (release + CANCELLED) rather than re-run `reserve` to confirm — the customer already received a 503, and a surprise CONFIRMED later is worse than a definite CANCELLED. Test `reconcilerCancelsStaleFailedOrdersOnceInventoryIsBack`. Still open: a stuck **PENDING** (crash before the reserve result) is not swept yet — the same scheduler would be the place.

### Amazon MSK notes (prod, from `infra/terraform/aws/msk.tf`)
- 3 brokers across 3 AZs, `default.replication.factor=3`, `min.insync.replicas=2` → with `acks=all` a write needs leader + 1 follower; one AZ down still accepts writes. `auto.create.topics.enable=false` → topics are created deliberately (Terraform / admin), unlike the dev broker where `orders.events` is auto-created with 3 partitions on first send.
- **IAM authentication** (`client_authentication.sasl.iam = true`): no passwords in the cluster; the pod's **IRSA** role is allowed `kafka-cluster:Connect/WriteData/ReadData/*Topic*` on topic ARNs `topic/<cluster>/*/orders.events*` and `topic/<cluster>/*/payments.events*`, and `AlterGroup/DescribeGroup` on group ARNs `group/<cluster>/*/notification-service`, `.../payment-service`, `.../order-service` (`irsa.tf`); the trust policy lists SAs `shopflow:order-service`, `shopflow:notification-service`, `shopflow:payment-service`. Client side: all three Kafka services ship `software.amazon.msk:aws-msk-iam-auth:2.3.9` as a `runtime` dependency and an `application-aws.yml` with `security.protocol: SASL_SSL`, `sasl.mechanism: AWS_MSK_IAM`, `sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;`, `sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler`; the prod overlay patches `SPRING_PROFILES_ACTIVE=aws` onto the three Deployments, so the default `application.yml` stays PLAINTEXT for the dev broker and nothing changes locally. The library takes credentials from the AWS default chain = the IRSA web-identity token. Honest note: this has never been run against a real MSK cluster. Port **9098** = IAM listener: the prod overlay's `kafka-bootstrap-servers` placeholder already uses `:9098`, the egress NetworkPolicy allows 9092 and 9098 to the VPC CIDR, and `service-accounts.yaml` gives order- and notification-service the IRSA-annotated ServiceAccounts (`serviceAccountName` patches) the `shopflow-kafka-clients` role trusts.
- TLS in transit (`client_broker = "TLS"`, `in_cluster = true`), broker logs to CloudWatch, EBS 100 GB per broker.
- Endpoints come from `terraform output` into the `shopflow-endpoints` ConfigMap (`behavior: replace` in `overlays/prod`); the in-cluster `kafka` StatefulSet and its NetworkPolicy are deleted by `$patch: delete`.

---

## 4. Interview Q&A (31)

**Q1. What is Kafka in one sentence, and why not RabbitMQ?**
A distributed, partitioned, replicated commit log: producers append, consumers pull and track their own offsets, data is retained and replayable. RabbitMQ is a smart broker/dumb consumer queue (routing, per-message ack, delete on consume) — better for task queues and complex routing; Kafka for event streams, replay, multiple independent consumer groups and high throughput.

**Q2. Why is a partition the unit of ordering and parallelism?**
Records in one partition are strictly ordered and read by exactly one consumer of a group; different partitions are independent. So ordering is per key (key → partition), and parallelism ≤ partitions.

**Q3. What happens with `acks=all` if a follower is slow?**
The write waits for all **in-sync** replicas. A follower that falls behind (`replica.lag.time.max.ms`) is removed from the ISR, so it no longer blocks. If ISR shrinks below `min.insync.replicas`, the broker rejects writes with `NotEnoughReplicasException` — availability sacrificed for durability.

**Q4. Idempotent producer vs idempotent consumer?**
Producer idempotence (`enable.idempotence=true`) removes duplicates from **producer retries** inside Kafka using sequence numbers. It does nothing about **application-level** redelivery (the outbox relay re-sending a row, or a consumer re-reading after a crash). For that the consumer must dedupe — ShopFlow's `processed_event`.

**Q5. What is the dual-write problem?**
Writing to two systems (DB + Kafka) without a shared transaction; a failure between them leaves them inconsistent. Fix: transactional outbox (write the event into the DB in the same transaction, relay it later) or CDC.

**Q6. Walk me through your outbox.**
`OrderService.saveWithEvent` saves the order and an `outbox_event` row in one `TransactionTemplate`. `OutboxRelay` runs every second (`@Scheduled`), selects up to 100 unpublished rows oldest-first with `FOR UPDATE SKIP LOCKED`, sends each to `orders.events` keyed by `orderRef` with `acks=all`, waits for the ack, marks `published_at`, and commits. Failures stop the batch so ordering holds; a backlog gauge feeds the `OutboxBacklogGrowing` alert.

**Q7. Why `TransactionTemplate` and not `@Transactional`?**
Self-invocation: `saveWithEvent` is a private method called from the same bean, so an annotation would be bypassed by the proxy and silently run without a transaction. Programmatic transactions do not have that problem and make the boundary explicit.

**Q8. What does `SKIP LOCKED` buy you?**
Multiple relay instances (pods) can run concurrently without blocking or double-publishing: each `SELECT ... FOR UPDATE SKIP LOCKED` returns only rows nobody else has locked. Without it, pods would either serialise on the same rows or publish duplicates.

**Q9. Is the outbox exactly-once?**
No — at-least-once. A crash between the Kafka ack and the `published_at` commit re-sends the row. Hence `eventId` + the idempotent consumer.

**Q10. Why is the event key `orderRef` and the dedupe key `eventId`?**
Key decides partition → ordering for all events of one order. `eventId` is unique per event; one order emits several events (`OrderConfirmed`, later `OrderCancelled`), so dedupe must be per event, not per order.

**Q11. Polling vs Debezium — why polling?**
No extra infrastructure (Kafka Connect, replication slots), one indexed query per second is negligible, 1s latency is fine for emails. I would move to Debezium when event volume or latency requirements grow.

**Q12. How does the consumer guarantee a notification is not sent twice?**
`@KafkaListener` + `@Transactional`: look up `processed_event` by `eventId`; if present, skip and count a duplicate; otherwise notify and insert the row in the same transaction. Offsets are committed only after the method returns (`enable-auto-commit: false`). payment-service does the same with no extra table: `payment.event_id` is the primary key, so `existsById(eventId)` is the check and the PK is the race guard (`redeliveredEventChargesOnlyOnce`).

**Q13. What is a poison pill and how do you handle it?**
A record that fails every time (bad JSON, unexpected type). `DefaultErrorHandler` retries with `ExponentialBackOff(500ms, ×2)` up to 10s, then `DeadLetterPublishingRecoverer` moves it to `orders.events.DLT` with exception headers; the partition continues. Someone monitors and replays the DLT.

**Q14. Retry in place vs retry topics?**
In place (ShopFlow) keeps per-key ordering but blocks the partition during retries → keep the budget short. Retry topics (`@RetryableTopic`) unblock the partition and allow long delays but break ordering for that key and multiply topics.

**Q15. Consumer lag is growing. First three things you check?**
(1) Are consumers alive and how many vs partitions (`kafka-consumer-groups --describe`)? (2) Processing time per record / a stuck poison pill retry loop (DLT count, error logs)? (3) Downstream slowness (DB pool, notification provider). Then scale: partitions → pods (KEDA on lag) → batch/optimise.

**Q16. What triggers a rebalance and how do you minimise its impact?**
Member join/leave/crash, `max.poll.interval.ms` exceeded, subscription/partition changes. Minimise: cooperative sticky assignor, static membership (`group.instance.id`), sane `max.poll.records`, graceful shutdown (ShopFlow pods have `preStop` + graceful shutdown so the consumer leaves the group cleanly).

**Q17. `auto.offset.reset=earliest` or `latest`?**
Only applies when the group has no committed offset. `earliest` replays retained history (ShopFlow: a new notification deployment catches up on existing orders); `latest` for "only new". Wrong choice = missed events or a surprise flood.

**Q18. What is `read_committed`?**
Consumer isolation level that skips records from aborted Kafka transactions and does not read past the last stable offset. Needed for Kafka-transactional producers; ShopFlow sets it defensively.

**Q19. How do you evolve an event schema without breaking consumers?**
Add fields only; consumers ignore unknown fields (`@JsonIgnoreProperties(ignoreUnknown = true)`, tested with `futureField`); never rename/retype; breaking change → new type/topic/version. Larger orgs: Schema Registry with compatibility checks in CI.

**Q20. Avro + Schema Registry vs JSON?**
Avro: compact, explicit contract, enforced compatibility, codegen; needs a registry and more tooling. JSON: readable, zero infra, relies on discipline. Pick by team size and number of consumers.

**Q21. KRaft vs ZooKeeper?**
KRaft replaces ZooKeeper with an internal Raft quorum for metadata — one system, faster failover, more partitions; Kafka 4.x is KRaft-only. ShopFlow runs a combined broker+controller node for dev; MSK manages this in prod.

**Q22. How do you secure Kafka?**
TLS in transit; authentication via SASL (SCRAM, OAUTHBEARER) or mTLS, on MSK **IAM** via IRSA; ACLs per topic/group; network-level restriction (ShopFlow: `kafka-ingress` NetworkPolicy allows only order-, notification- and payment-service to 9092; MSK security group allows only EKS node SGs; the IAM policy names the two topics and the three consumer groups, so a compromised pod cannot read a topic it does not own).

**Q23. Does Kafka replace your REST call to inventory?**
Not for the reservation: it needs an immediate answer, so it stays synchronous (orchestration). Kafka carries the rest of the saga — payment (`orders.events` → payment-service → `payments.events` → order-service) and notifications — as choreography. Moving reservation to events too would decouple availability but introduce a PENDING state the UI must handle.

**Q24. How do you trace a request across Kafka?**
Micrometer observation on `KafkaTemplate` and the listener container (`observation-enabled: true`) propagates `traceparent` in record headers; Tempo shows order-service HTTP span → producer span → notification-service consumer span. Logs carry `trace.id` for correlation (see note 11).

**Q25. How do you test Kafka code without Docker?**
`spring-kafka-test` `@EmbeddedKafka` starts an in-process broker; `application-test.yml` points `bootstrap-servers` at `${spring.embedded.kafka.brokers}`. Order test consumes with a raw `KafkaConsumer`; notification tests produce with `KafkaTemplate` and assert on metrics/DB via Awaitility. Testcontainers Kafka is the alternative for a real broker.

**Q26. Compacted topic — when?**
When you only care about the latest value per key (user profile, config, changelog for a KTable). Not for an event history like `orders.events` where every event matters.

**Q27. What is `min.insync.replicas` and why 2 with RF 3?**
Minimum ISR count for an `acks=all` write to succeed. RF 3 / min ISR 2 tolerates one broker (or AZ) down while still guaranteeing two copies of every acknowledged write. RF 3 / min ISR 3 would make a single broker failure block all writes; min ISR 1 would risk data loss.

**Q28. Doesn't the outbox table grow forever?**
Not anymore: `OutboxRelay.cleanup` runs daily at 03:30 UTC (`outbox.cleanup.cron`) and deletes rows with `published_at < now - 7d` (`outbox.cleanup.retention`) via `OutboxRepository.deletePublishedBefore`; unpublished rows are never deleted, so a backlog survives housekeeping. Retention equals the broker's 7-day log retention. At high volume I would partition by day and drop partitions instead of bulk deletes.

**Q29. How does your saga handle a step that times out?**
The order is FAILED (outcome unknown) and the client gets 503 with the `orderId`. `OrderReconciler` runs every 60s and, for FAILED **or PENDING** orders older than a 2-minute grace (a pod that died mid-request leaves PENDING), calls inventory's idempotent `release` and marks them CANCELLED with an `OrderCancelled` event; if inventory is still down it stops the batch and retries next minute. So a timeout always converges to a definite state and leaked reservations are freed without a human. Honest gap: there is no timeout from CONFIRMED yet — if payment-service is down, orders wait (with stock reserved) until it is back; the symptom is `KafkaConsumerLagHigh` on group `payment-service`.

**Q30. Walk me through the payment step.**
`OrderConfirmed` lands on `orders.events`; group `payment-service` reads it (notification-service reads the same record with its own offsets). `OrderConfirmedListener` ignores every other type, skips the record if a `payment` row with that `eventId` exists, otherwise charges `PriceList × quantity` through the `PaymentGateway` interface (`FakePaymentGateway` declines above `payment.card-limit`), and in one transaction saves the `payment` row plus a `PaymentCaptured` or `PaymentFailed` outbox row. The shared `OutboxRelay` publishes it to `payments.events` keyed by `orderRef`. order-service (group `order-service`) dedupes on `processed_event` and either marks the order PAID or releases the stock and cancels it with `failureReason = "payment failed: <reason>"`. Both branches run only while the order is still CONFIRMED, so a duplicate or late event is a no-op. Failures in either listener roll back the DB transaction, leave the offset uncommitted, retry with backoff and finally go to the topic's `.DLT`.

**Q31. Why does order-service need a `processed_event` table when payment-service manages with a primary key?**
payment-service creates exactly one row per triggering event, so the row's PK can double as the inbox. order-service *updates* an existing row (the order) in reaction to two different event types; there is no new row whose key could be the `eventId`, and the status guard alone would not count duplicates or protect a `PaymentFailed` arriving twice from calling `release` twice (harmless here because release is idempotent, but not in general). Hence the general inbox table (`V5__processed_event.sql`) with `ProcessedEventCleanup` to keep it bounded.

---

## 5. Scenarios (practise out loud)

**S1. Customers received the same "order confirmed" email twice.**
Hypotheses: (a) consumer redelivery after a crash/rebalance — should be caught by `processed_event`; check `notifications_duplicates_total` rising (then the dedupe *worked* and the duplicate is upstream of the log). (b) The email was sent but the DB commit failed (provider call before commit) → redelivery resends. (c) Two *different* events (`eventId`s) for the same order — e.g. a client retried `POST /orders` **without** an `Idempotency-Key` → two orders → two legitimate emails. Check `orders_total`, the outbox rows for that `orderRef`, and the DLT. Fixes: idempotent provider call (dedup id), move the send after commit, make the idempotency key mandatory at the gateway.

**S2. notification-service is "stuck": lag on partition 1 grows, partitions 0 and 2 are fine.**
A poison pill (or a record hitting a bug) is being retried on that partition. Logs show the same offset failing; `DefaultErrorHandler` should move it to the DLT after ~10s — if the lag persists, the exception may be thrown in a way the handler cannot recover (e.g. an error in a `@Transactional` commit after the listener returned, or `max.poll.interval.ms` exceeded during retries causing a rebalance loop). Inspect the DLT headers (`kafka_dlt-exception-message`), fix the consumer or the data, replay from the DLT. Add an alert on DLT message count.

**S3. `KafkaConsumerLagHigh` fires for 10 minutes after a marketing push.**
Not a bug — throughput. Partitions (3 locally / 6 on MSK) cap useful consumers; check pods ≤ partitions. Speed up processing (batch listener, async provider calls, bigger DB pool — notification uses `DB_POOL_SIZE=5`), scale pods with KEDA on lag, and if needed increase partitions **on a new topic** or accept the key remapping. Retention (7 days) means nothing is lost while catching up. The rule uses `kafka_consumer_fetch_manager_records_lag_max`, the series the consumer really exports (see §2).

**S4. Rebalance storm: the consumer group rebalances every few minutes.**
Causes: processing a poll batch takes longer than `max.poll.interval.ms` (5 min) → member kicked → rejoin → repeat; pods OOMKilled / failing liveness and restarting; HPA flapping. Check `kubectl get pods` restarts, GC/heap, batch size. Fixes: smaller `max.poll.records`, longer `max.poll.interval.ms`, cooperative sticky assignor, static membership, HPA stabilisation windows (ShopFlow uses 300s scale-down), fix the crash.

**S5. The only Kafka broker (dev) is down. What do users see?**
Nothing — `POST /orders` still returns 201/200 because the event is only written to Postgres. `outbox_unpublished` climbs → `OutboxBacklogGrowing` pages after 5 minutes over 100; relay logs `could not publish outbox event ... will retry`. When the broker returns, the relay drains the backlog oldest-first in batches of 100 per second. On MSK with RF 3 / min ISR 2 a single broker loss is invisible (leader election); two brokers down → writes fail → same backlog behaviour. This is the whole point of the outbox: **decoupled availability**.

**S6. Outbox backlog keeps growing but Kafka is healthy.**
The relay is not running or is stuck: `@EnableScheduling` missing after a refactor, the scheduler thread blocked by a `send().get(5s)` loop against a reachable-but-unresponsive broker (each failure returns early, 1s delay, so throughput collapses), `OutboxRepository.findUnpublished` slow (index dropped?), or a lock held on rows by a dead transaction. Check `outbox_unpublished`, relay logs, `pg_locks`, and thread dumps. Also check producer settings: if `KAFKA_BOOTSTRAP_SERVERS` points at a wrong host, `send()` times out at 5s per row.

**S7. Events of one order are processed out of order (`OrderCancelled` before `OrderConfirmed`).**
Ordering holds only per partition and per key. Suspects: partition count changed on the topic (new mapping), a producer somewhere sending without the `orderRef` key, concurrent processing inside the consumer (custom executor), or retry topics. In ShopFlow the relay also stops at the first failure to avoid skipping ahead. Design mitigation: make the consumer order-tolerant (state machine that ignores stale transitions, or compare `occurredAt`).

**S8. A producer deploy added a field and renamed `quantity` to `qty`; notification-service started sending "0 x PS5".**
Renames are breaking: the consumer copy of `OrderEvent` has `quantity`, Jackson left it default `0` (an `int` cannot be null) and no exception was thrown. Prevention: additive-only policy, a contract test shared between services (or Schema Registry with BACKWARD compatibility in CI), `@JsonProperty` aliases during migration, and validation in the consumer (`quantity > 0` else DLT). Recovery: fix the producer, replay the affected offsets (Kafka retains them) — the dedupe table means already-processed good events are skipped.

---

## 6. Common mistakes (say you avoided them)

- Calling `kafkaTemplate.send()` right after `repository.save()` and believing `@Transactional` covers both (dual write).
- Putting `@Transactional` on a private/self-invoked method (ignored); ShopFlow uses `TransactionTemplate`.
- Marking an outbox row published without waiting for the broker ack (`send()` is async).
- Deduping on the business id (`orderRef`) instead of the event id → later events of the same entity dropped.
- `enable.auto.commit=true` with side effects → lost messages on crash.
- No DLT: one bad record blocks a partition forever. Or a DLT nobody monitors.
- `JsonDeserializer` without `ErrorHandlingDeserializer` → infinite deserialization loop.
- Scaling consumer pods beyond the partition count and expecting more throughput.
- Increasing partitions on a live topic and assuming per-key ordering survives.
- Replication factor 1 / `min.insync.replicas=1` in production (fine only for the dev broker).
- `auto.offset.reset=latest` on a brand-new consumer and wondering where the history went (or `earliest` and getting flooded).
- Alert rules referencing metric names that were never checked against `/actuator/prometheus` (ShopFlow's `KafkaConsumerLagHigh` had exactly this bug; fixed and now pinned by a test).
- Fat events with PII (emails, addresses) retained for 7 days on the broker without encryption or a data-retention review.
- An outbox with no retention job (the table becomes the biggest in the DB and the `published_at IS NULL` scan slows down) — ShopFlow deletes published rows after 7 days.

## 7. Operations cheat sheet (run against the compose broker)

```bash
K=/opt/kafka/bin; B=localhost:9092
docker compose exec kafka $K/kafka-topics.sh --bootstrap-server $B --list
docker compose exec kafka $K/kafka-topics.sh --bootstrap-server $B --describe --topic orders.events      # partitions, leader, ISR
docker compose exec kafka $K/kafka-consumer-groups.sh --bootstrap-server $B --describe --group notification-service
#   TOPIC  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID   <- lag per partition, who owns it
docker compose exec kafka $K/kafka-console-consumer.sh --bootstrap-server $B --topic orders.events \
  --from-beginning --property print.key=true --property print.headers=true        # see traceparent header + key
docker compose exec kafka $K/kafka-console-consumer.sh --bootstrap-server $B --topic orders.events.DLT \
  --from-beginning --property print.headers=true                                   # kafka_dlt-exception-message etc.
docker compose exec kafka $K/kafka-console-producer.sh --bootstrap-server $B --topic orders.events \
  --property parse.key=true --property key.separator=:                              # type  bad:this is not json  -> poison pill demo
# replay a group from the beginning (group must be inactive = scale notification-service to 0 first)
docker compose exec kafka $K/kafka-consumer-groups.sh --bootstrap-server $B --group notification-service \
  --topic orders.events --reset-offsets --to-earliest --execute
# move a group to a timestamp / shift by N
#   --to-datetime 2026-10-04T10:00:00.000 | --shift-by -100
docker compose exec kafka $K/kafka-topics.sh --bootstrap-server $B --alter --topic orders.events --partitions 6   # key mapping changes!
docker compose exec kafka $K/kafka-configs.sh --bootstrap-server $B --alter --entity-type topics \
  --entity-name orders.events --add-config retention.ms=604800000
# what the apps see
curl -s localhost:8081/actuator/prometheus | grep -E 'outbox_unpublished|kafka_producer_record_send_total'
curl -s localhost:8083/actuator/prometheus | grep -E 'records_lag|notifications_(sent|duplicates)'
docker compose exec postgres psql -U orders -d orders -c "select event_type, count(*) filter (where published_at is null) pending, count(*) total from outbox_event group by 1"
docker compose exec postgres psql -U notifications -d notifications -c "select count(*) from processed_event"
```
Kubernetes equivalents: `kubectl exec -n shopflow kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group notification-service`; scale consumers with `kubectl scale deploy/notification-service --replicas=3` (never above the partition count for throughput). On MSK the same CLI works with the IAM client properties file (`--command-config client.properties`).

---

## 8. Whiteboard: extending ShopFlow to an event-driven reservation (the question you will get)

```
POST /orders ──▶ order-service: save PENDING + outbox OrderPlaced ──▶ orders.commands (key orderRef)
                                                                            │
                                               inventory-service  ◀─────────┘  @KafkaListener group "inventory"
                                               reserve(orderRef, sku, qty)  (already idempotent by orderRef)
                                               outbox StockReserved | StockRejected ──▶ inventory.events (key orderRef)
                                                                            │
order-service @KafkaListener group "order-status" ◀─────────────────────────┘
   CONFIRMED / REJECTED  → outbox OrderConfirmed/OrderRejected → orders.events → notification-service (unchanged)
Client: 202 Accepted + Location, then GET /orders/{id} (or WebSocket/SSE) until status != PENDING
```
Points to make:
- **What improves**: inventory downtime no longer fails orders (they queue as PENDING); no retry/breaker tuning for the hot path; natural backpressure; other consumers can join.
- **What gets harder**: the client needs polling or push; a stuck PENDING needs a **timeout** (scheduler marks `FAILED` after N minutes and emits `OrderExpired`, inventory must then release — a compensating event; the existing `OrderReconciler` is the natural home, it already does release + CANCELLED for FAILED); you now operate two more topics and a consumer in order-service; end-to-end latency rises from ~50 ms to seconds.
- **Idempotency stays the same**: `orderRef` on the reservation, `eventId` on every consumer; the outbox code is already generic (`aggregateType`, `eventType`, `payload`).
- **Orchestration vs choreography** again: here inventory reacts to `OrderPlaced` (choreography). A saga orchestrator (Temporal, Camunda, or a state machine in order-service) is the alternative when the flow grows to payment + shipping + email and you need a single place to see where each order is.
- **Testing**: same `@EmbeddedKafka` approach; the inventory test would publish `OrderPlaced` and assert stock moved once even when the message is delivered twice.
- **Where ShopFlow stops today**: this design is the first row of the improvement table in [01](01-project-walkthrough.md) — say that it is deliberately not done yet because the synchronous answer was a product requirement, and that the outbox/consumer building blocks are already proven in production code and tests.

> Tip: Kafka round me sabse zyada marks "trade-off" batane pe milte hain — ordering vs parallelism, latency vs durability (`acks`), simplicity (polling) vs latency (CDC). Har answer me ek trade-off daalo.
