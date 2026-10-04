package com.shopflow.order.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Contract of payment-service's "payments.events" topic (consumer-side copy). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(UUID eventId, String type, String orderRef, BigDecimal amount,
                           String failureReason, Instant occurredAt) {

    public static final String TOPIC = "payments.events";
}
