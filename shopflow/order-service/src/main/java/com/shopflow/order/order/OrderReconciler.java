package com.shopflow.order.order;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background settlement of FAILED orders (see OrderService.reconcileFailedOrders). */
@Component
public class OrderReconciler {

    private final OrderService orderService;
    private final Duration grace;

    public OrderReconciler(OrderService orderService, @Value("${orders.reconcile.grace:2m}") Duration grace) {
        this.orderService = orderService;
        this.grace = grace;
    }

    @Scheduled(fixedDelayString = "${orders.reconcile.delay:60s}")
    public void run() {
        orderService.reconcileFailedOrders(grace);
    }
}
