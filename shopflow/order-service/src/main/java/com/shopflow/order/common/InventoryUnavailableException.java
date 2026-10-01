package com.shopflow.order.common;

public class InventoryUnavailableException extends RuntimeException {

    private final long orderId;

    public InventoryUnavailableException(long orderId, Throwable cause) {
        super("Inventory service unavailable, order " + orderId + " marked FAILED", cause);
        this.orderId = orderId;
    }

    public long getOrderId() {
        return orderId;
    }
}
