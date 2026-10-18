package com.shopflow.order.outbox;

import java.time.Instant;
import java.util.UUID;

import com.shopflow.order.order.Order;
import com.shopflow.order.order.OrderStatus;

/** What other services receive on the "orders.events" topic. eventId lets consumers de-duplicate. */
public record OrderEvent(UUID eventId, String type, String orderRef, String sku, int quantity,
                         OrderStatus status, Instant occurredAt) {

    public static final String TOPIC = "orders.events";

    public static OrderEvent from(Order order) {
        return new OrderEvent(UUID.randomUUID(), "Order" + pascal(order.getStatus().name()),
                order.getOrderRef(), order.getSku(), order.getQuantity(), order.getStatus(), Instant.now());
    }

    // CONFIRMED -> Confirmed, REFUND_PENDING -> RefundPending. The event type is part of the contract
    // consumers switch on, so underscores must not leak into it (would give "Refund_pending").
    private static String pascal(String status) {
        StringBuilder sb = new StringBuilder();
        for (String part : status.split("_")) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
