# KafkaMessageDeadLettered (ticket)

**Fires when:** a consumer exhausted its retries (exponential backoff 500ms×2, max ~10s — `KafkaErrorConfig`) and moved a record to a **dead-letter topic** (`orders.events.DLT` or `payments.events.DLT`). The partition keeps flowing (that is the point of the DLT), but the dead-lettered event's side effect **did not happen**: an email not sent (notification-service), a payment outcome not applied to the order (order-service), or a charge not made (payment-service). Nothing consumes the DLTs, so this alert is the only thing that tells a human (Walk 40).

> Not a page: the pipeline is healthy and nothing is customer-facing-down. But every DLT record is a lost event until replayed — never ignore it. The `application` label says which service dead-lettered.

```sh
# what landed on the DLT and why (the recoverer stamps the exception into headers)
kn exec kafka-0 -- kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders.events.DLT --from-beginning --max-messages 10 \
  --property print.headers=true --property print.key=true
# same for payments.events.DLT when application="order-service"
# which service + when
kn logs deploy/notification-service --since=1h | grep -iE "dead|DLT|ErrorHandler" | tail -20
```

| Symptom on the DLT record | Cause | Fix |
|---|---|---|
| not JSON / missing fields (`DeserializationException`, `JsonProcessingException` in the header) | a producer bug or a manually produced bad record (poison pill) | fix the producer/schema; the bad record usually cannot be replayed — document and drop it |
| valid event, `DataAccessException`/timeout in the header | DB/downstream was down longer than the ~10s retry budget | fix the outage, then **replay**: re-produce the record from the DLT onto the original topic (consumers are idempotent — `processed_event`/PK dedupe makes replay safe) |
| the same event dead-letters again on replay | a real handler bug for that event shape | fix the consumer, add a test with that payload, then replay |

**Replay (after fixing the cause):** consume the record from the DLT and produce it back to the original topic with the **same key** (orderRef) so it lands on the same partition. Idempotent consumers make a duplicate harmless, so replaying "too much" is safe; losing the record is not.

**Verify:** `kafka_dead_lettered_total` stops increasing; the replayed events' side effects exist (email row / order status / payment row); nothing new on the DLTs. Post-mortem if it was an outage that outran the retry budget — consider whether the backoff budget (10s) or the alert threshold needs tuning, not whether to disable the alert.
