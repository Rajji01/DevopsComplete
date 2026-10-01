package com.shopflow.inventory.common;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String sku, int requested) {
        super("Insufficient stock for " + sku + " (requested " + requested + ")");
    }
}
