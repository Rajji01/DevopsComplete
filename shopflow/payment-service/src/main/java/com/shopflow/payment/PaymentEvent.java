package com.shopflow.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What payment-service publishes on "payments.events". order-service reacts to it. */
public record PaymentEvent(UUID eventId, String type, String orderRef, BigDecimal amount,
                           String failureReason, Instant occurredAt) {

    public static final String TOPIC = "payments.events";

    static PaymentEvent captured(String orderRef, BigDecimal amount) {
        return new PaymentEvent(UUID.randomUUID(), "PaymentCaptured", orderRef, amount, null, Instant.now());
    }

    static PaymentEvent failed(String orderRef, BigDecimal amount, String reason) {
        return new PaymentEvent(UUID.randomUUID(), "PaymentFailed", orderRef, amount, reason, Instant.now());
    }
}
