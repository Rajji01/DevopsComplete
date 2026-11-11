package com.shopflow.ledger.saga;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ledger-service's own view of what payment-service publishes on {@code payments.events}. Each
 * consumer keeps its own copy of the event contract (order-service has one too) rather than sharing
 * a class across services — so a producer adding a field it does not need cannot break this build,
 * and {@code @JsonIgnoreProperties} keeps an unknown new field from failing deserialization. Only
 * the fields the ledger actually posts from are declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(UUID eventId, String type, String orderRef, BigDecimal amount) {

    public static final String TOPIC = "payments.events";
}
