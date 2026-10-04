package com.shopflow.order.order;

public enum OrderStatus {
    /** saved, stock not reserved yet */
    PENDING,
    /** stock reserved in inventory-service; waiting for payment-service */
    CONFIRMED,
    /** payment captured: the order is final */
    PAID,
    /** inventory said no (out of stock / unknown SKU) */
    REJECTED,
    /** inventory-service unreachable; outcome unknown, safe to cancel (release is idempotent) */
    FAILED,
    /** cancelled by the customer, stock released */
    CANCELLED
}
