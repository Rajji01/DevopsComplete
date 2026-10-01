package com.shopflow.order.common;

import com.shopflow.order.order.OrderStatus;

public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(long id, OrderStatus status) {
        super("Order " + id + " cannot be cancelled in status " + status);
    }
}
