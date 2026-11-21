package com.shopflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OrderEventListener(ProcessedEventRepository processedEvents, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * One consumer group: each partition is read by exactly one pod, so scaling pods scales
     * throughput up to the partition count. Offsets are committed only after this method
     * returns, so a crash mid-way re-delivers the record (hence the idempotency check).
     */
    @KafkaListener(topics = "orders.events", groupId = "notification-service")
    @Transactional
    public void onOrderEvent(String payload) throws Exception {
        OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);

        if (processedEvents.existsById(event.eventId())) {
            meterRegistry.counter("notifications.duplicates").increment();
            log.info("duplicate event {} for order {} skipped", event.eventId(), event.orderRef());
            return;
        }

        // CAUTION when notify() becomes a real SES/SNS/FCM call: it is an IRREVERSIBLE external
        // side-effect running inside this @Transactional method, BEFORE the ProcessedEvent row
        // commits. Kafka is at-least-once (offset commits only after this returns), so if the send
        // succeeds but the DB commit then fails, the row never lands, the record is re-delivered,
        // and the customer gets a SECOND email — the dual-write problem (send + dedup-row can't
        // commit atomically). The existsById check above shrinks the window but cannot close it.
        // Correct options when wiring a real provider: (a) give the send a provider-side idempotency
        // key = event.eventId() so a replay is a no-op at the provider, or (b) outbox the "send"
        // command to a worker that owns the retry — do NOT just trust this in-tx ordering. A stub
        // log is safe (a duplicate log line harms nothing), so this stays as-is until a real send lands.
        notify(event);
        processedEvents.save(new ProcessedEvent(event.eventId(), event.orderRef()));
        meterRegistry.counter("notifications.sent", "type", event.type()).increment();
    }

    // real life: SES / SNS / FCM call here (idempotency-keyed on eventId — see the caution above);
    // the log line stands in for it
    private void notify(OrderEvent event) {
        String message = switch (event.type()) {
            case "OrderConfirmed" -> "Your order %s for %d x %s is confirmed".formatted(event.orderRef(), event.quantity(), event.sku());
            case "OrderRejected" -> "Sorry, %s is out of stock (order %s)".formatted(event.sku(), event.orderRef());
            case "OrderCancelled" -> "Order %s was cancelled".formatted(event.orderRef());
            default -> "Order %s is now %s".formatted(event.orderRef(), event.status());
        };
        log.info("EMAIL -> customer: {}", message);
    }
}
