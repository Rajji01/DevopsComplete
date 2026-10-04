package com.shopflow.payment;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Contract of order-service's "orders.events" topic (consumer-side copy, unknown fields ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(UUID eventId, String type, String orderRef, String sku, int quantity,
                         String status, Instant occurredAt) {
}
