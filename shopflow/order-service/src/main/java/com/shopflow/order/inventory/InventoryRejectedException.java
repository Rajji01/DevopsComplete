package com.shopflow.order.inventory;

/**
 * Inventory answered with a business "no" (409 out of stock, 404 unknown SKU).
 * Not a technical failure: must not be retried and must not open the circuit breaker
 * (see ignore-exceptions in application.yml).
 */
public class InventoryRejectedException extends RuntimeException {

    public InventoryRejectedException(String message) {
        super(message);
    }
}
