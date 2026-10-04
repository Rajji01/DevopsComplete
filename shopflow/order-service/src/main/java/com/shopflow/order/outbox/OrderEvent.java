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
        return new OrderEvent(UUID.randomUUID(), "Order" + capitalize(order.getStatus().name()),
                order.getOrderRef(), order.getSku(), order.getQuantity(), order.getStatus(), Instant.now());
    }

    private static String capitalize(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}
