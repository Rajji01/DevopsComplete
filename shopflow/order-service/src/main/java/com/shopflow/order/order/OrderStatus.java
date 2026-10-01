package com.shopflow.order.order;

public enum OrderStatus {
    /** saved, stock not reserved yet */
    PENDING,
    /** stock reserved in inventory-service */
    CONFIRMED,
    /** inventory said no (out of stock / unknown SKU) */
    REJECTED,
    /** inventory-service unreachable; outcome unknown, safe to cancel (release is idempotent) */
    FAILED,
    /** cancelled by the customer, stock released */
    CANCELLED
}
