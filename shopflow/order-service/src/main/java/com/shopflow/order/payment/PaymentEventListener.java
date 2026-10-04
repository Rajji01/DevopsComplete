package com.shopflow.order.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.order.order.OrderService;

/**
 * Closes the saga: PaymentCaptured -> PAID, PaymentFailed -> release stock + CANCELLED.
 * No central orchestrator knows the whole flow; each service reacts to the previous step's event
 * (choreography). The trade-off: the flow is harder to see in one place, which is what tracing is for.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final OrderService orderService;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(OrderService orderService, ProcessedEventRepository processedEvents, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = PaymentEvent.TOPIC, groupId = "order-service")
    @Transactional
    public void onPaymentEvent(String payload) throws Exception {
        PaymentEvent event = objectMapper.readValue(payload, PaymentEvent.class);
        if (processedEvents.existsById(event.eventId())) {
            log.info("duplicate payment event {} skipped", event.eventId());
            return;
        }
        switch (event.type()) {
            case "PaymentCaptured" -> orderService.onPaymentCaptured(event.orderRef());
            case "PaymentFailed" -> orderService.onPaymentFailed(event.orderRef(), event.failureReason());
            default -> log.warn("unknown payment event type {}", event.type());
        }
        processedEvents.save(new ProcessedEvent(event.eventId()));
    }
}
