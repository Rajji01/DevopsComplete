package com.shopflow.order.common;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used by a different request");
    }
}
