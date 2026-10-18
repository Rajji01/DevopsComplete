package com.shopflow.order.order;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Background settlement + a stuck-order gauge.
 *  - reconcileFailedOrders: FAILED/PENDING orders past the grace period are cancelled (no money moved).
 *  - countStuckConfirmed: CONFIRMED/REFUND_PENDING orders past a LONGER grace are only counted, never
 *    auto-cancelled (payment may be in flight). The gauge orders_stuck_confirmed drives an alert.
 * The scheduled run refreshes an AtomicLong so Prometheus scrapes read a cached value, not a DB query.
 */
@Component
public class OrderReconciler {

    private final OrderService orderService;
    private final Duration reconcileGrace;
    private final Duration stuckGrace;
    private final AtomicLong stuckConfirmed = new AtomicLong();

    public OrderReconciler(OrderService orderService, MeterRegistry meterRegistry,
                           @Value("${orders.reconcile.grace:2m}") Duration reconcileGrace,
                           @Value("${orders.stuck.grace:10m}") Duration stuckGrace) {
        this.orderService = orderService;
        this.reconcileGrace = reconcileGrace;
        this.stuckGrace = stuckGrace;
        meterRegistry.gauge("orders.stuck.confirmed", stuckConfirmed);
    }

    @Scheduled(fixedDelayString = "${orders.reconcile.delay:60s}")
    public void run() {
        orderService.reconcileFailedOrders(reconcileGrace);
        stuckConfirmed.set(orderService.countStuckConfirmed(stuckGrace));
    }
}
