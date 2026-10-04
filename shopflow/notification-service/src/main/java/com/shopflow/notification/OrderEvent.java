package com.shopflow.notification;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Copy of order-service's event contract. Unknown fields are ignored so the producer can add fields freely. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(UUID eventId, String type, String orderRef, String sku, int quantity,
                         String status, Instant occurredAt) {
}
