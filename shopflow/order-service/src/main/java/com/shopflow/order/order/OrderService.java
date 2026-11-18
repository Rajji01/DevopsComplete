package com.shopflow.order.order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import com.shopflow.order.common.IdempotencyKeyConflictException;
import com.shopflow.order.common.InventoryUnavailableException;
import com.shopflow.order.common.OrderNotCancellableException;
import com.shopflow.order.common.OrderNotFoundException;
import com.shopflow.order.inventory.InventoryClient;
import com.shopflow.order.inventory.InventoryRejectedException;
import com.shopflow.order.outbox.OrderEvent;
import com.shopflow.outbox.OutboxPublisher;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Deliberately NOT one big @Transactional method: holding a DB transaction (and connection)
 * open while waiting on a remote HTTP call exhausts the pool under load. Instead each step
 * is saved on its own, and the order status records how far we got.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record PlacedOrder(Order order, boolean created) {
    }

    private final OrderRepository orderRepository;
    private final OutboxPublisher outbox;
    private final InventoryClient inventoryClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orderRepository, OutboxPublisher outbox,
                        InventoryClient inventoryClient, MeterRegistry meterRegistry,
                        TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
        this.inventoryClient = inventoryClient;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    public PlacedOrder placeOrder(String sku, int quantity, String idempotencyKey, Caller caller) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().isOwnedBy(caller.customerId())) {
                    // same key from a different user: never replay someone else's order to them
                    throw new IdempotencyKeyConflictException(idempotencyKey);
                }
                log.info("idempotent replay for key {}, returning order {}", idempotencyKey, existing.get().getId());
                return new PlacedOrder(existing.get(), false);
            }
        }

        Order order;
        try {
            order = orderRepository.save(Order.pending(sku, quantity, idempotencyKey, caller.customerId()));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same Idempotency-Key raced past the pre-check above (a double
            // click lands both before either commits); the unique index on idempotency_key made one
            // of them lose. Same answer as inventory's reservation race (Walk 45): the loser returns
            // the winner's order instead of a 409 — the pre-check is an optimization, the constraint
            // is the guarantee, and this catch is the recovery.
            var winner = idempotencyKey == null ? java.util.Optional.<Order>empty()
                    : orderRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isEmpty()) {
                throw e; // some other constraint: not the key race, don't swallow it
            }
            if (!winner.get().isOwnedBy(caller.customerId())) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            meterRegistry.counter("orders.idempotency.race").increment();
            log.info("idempotency race for key {}: returning winner order {}", idempotencyKey, winner.get().getId());
            return new PlacedOrder(winner.get(), false);
        }
        try {
            inventoryClient.reserve(order.getOrderRef(), sku, quantity);
            order.confirm();
        } catch (InventoryRejectedException e) {
            order.reject(e.getMessage());
        } catch (RestClientException | CallNotPermittedException e) {
            log.warn("inventory unavailable for order {}: {}", order.getOrderRef(), e.getMessage());
            order.fail("inventory-service unavailable");
            saveWithEvent(order);
            throw new InventoryUnavailableException(order.getId(), e);
        }

        order = saveWithEvent(order);
        log.info("order {} {}", order.getOrderRef(), order.getStatus());
        return new PlacedOrder(order, true);
    }

    /** 404 (not 403) for someone else's order: don't reveal that the id exists. */
    public Order get(long id, Caller caller) {
        return orderRepository.findById(id)
                .filter(caller::mayAccess)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public Page<Order> list(Pageable pageable, Caller caller) {
        return caller.support()
                ? orderRepository.findAll(pageable)
                : orderRepository.findByCustomerId(caller.customerId(), pageable);
    }

    /**
     * Cancel. Three shapes depending on how far the saga got:
     *  - CONFIRMED / FAILED: no money has moved, so release stock now and CANCELLED immediately.
     *  - PAID: money has moved, so we cannot cancel synchronously. Move to REFUND_PENDING and emit
     *    OrderRefundPending; payment-service refunds and replies, then onPaymentRefunded finishes it.
     *  - anything else (PENDING, already cancelled/refunding): not cancellable.
     */
    public Order cancel(long id, Caller caller) {
        Order order = get(id, caller);
        if (order.isPaid()) {
            order.requestRefund();
            Order saved = saveWithEvent(order);   // event type OrderRefundPending -> payment-service refunds
            log.info("order {} REFUND_PENDING (refund requested)", order.getOrderRef());
            return saved;
        }
        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(id, order.getStatus());
        }
        inventoryClient.release(order.getOrderRef());
        order.cancel();
        return saveWithEvent(order);
    }

    /** Saga step 3: payment-service answered. Every branch is idempotent (status-guarded). */
    public void onPaymentCaptured(String orderRef) {
        orderRepository.findByOrderRef(orderRef).ifPresent(order -> {
            if (order.isConfirmed()) {
                order.markPaid();
                saveWithEvent(order);
                log.info("order {} PAID", orderRef);
            } else if (order.isCancelled()) {
                // Cancel/charge race (Walk 43): the customer cancelled the CONFIRMED order while
                // payment-service was still charging — with consumer lag that window is minutes, not
                // millis. The money HAS been captured for an order we already cancelled, so compensate:
                // REFUND_PENDING re-enters the normal refund saga (OrderRefundPending event ->
                // payment-service refunds -> PaymentRefunded -> REFUNDED). Stock was already released
                // by cancel(); the release in onPaymentRefunded is idempotent, so the replay is safe.
                order.requestRefund();
                saveWithEvent(order);
                meterRegistry.counter("orders.cancel.charge.race").increment();
                log.warn("order {} was CANCELLED but the payment captured anyway (cancel/charge race) -> REFUND_PENDING, refunding", orderRef);
            }
            // PAID / REFUND_PENDING / REFUNDED: duplicate or late delivery, nothing to do
        });
    }

    /** Compensation: the card was declined, so give the stock back and cancel. */
    public void onPaymentFailed(String orderRef, String reason) {
        orderRepository.findByOrderRef(orderRef).filter(Order::isConfirmed).ifPresent(order -> {
            inventoryClient.release(orderRef);
            order.cancel("payment failed: " + reason);
            saveWithEvent(order);
            log.info("order {} CANCELLED after payment failure: {}", orderRef, reason);
        });
    }

    /**
     * Refund was declined by the processor (PaymentRefundFailed). We do NOT advance the order: it stays
     * REFUND_PENDING (the money did not come back), and the OrdersStuckConfirmed gauge/alert — which
     * counts REFUND_PENDING — pages a human to resolve it. We only record that it happened.
     */
    public void onRefundFailed(String orderRef, String reason) {
        orderRepository.findByOrderRef(orderRef).filter(Order::isRefundPending).ifPresent(order ->
                log.warn("refund failed for order {} ({}); stays REFUND_PENDING for manual handling", orderRef, reason));
        meterRegistry.counter("orders.refund.failed").increment();
    }

    /** Saga close for a refund: money is back, so release the stock and mark REFUNDED. Status-guarded. */
    public void onPaymentRefunded(String orderRef) {
        orderRepository.findByOrderRef(orderRef).filter(Order::isRefundPending).ifPresent(order -> {
            inventoryClient.release(orderRef);   // the item returns to available stock; release is idempotent
            order.markRefunded();
            saveWithEvent(order);
            log.info("order {} REFUNDED (money returned, stock released)", orderRef);
        });
    }

    /**
     * FAILED = we never learned whether inventory reserved the stock (timeout, breaker open);
     * PENDING for a long time = the pod crashed mid-request. Both are "outcome unknown".
     * After a grace period, give up: release is idempotent (a no-op if nothing was reserved),
     * so calling it is always safe, and the customer gets a definite CANCELLED instead of limbo.
     * Runs on a schedule (OrderReconciler); returns how many orders it settled.
     */
    public int reconcileFailedOrders(Duration grace) {
        // PENDING this old means the pod died between "save PENDING" and "save outcome": same limbo.
        // FAILED = inventory outcome unknown. Both are safe to cancel (no money moved; release is a no-op
        // if nothing was reserved). CONFIRMED is NOT here on purpose: see countStuckConfirmed().
        var stale = orderRepository.findTop100ByStatusInAndUpdatedAtBefore(
                List.of(OrderStatus.FAILED, OrderStatus.PENDING), Instant.now().minus(grace));
        int settled = 0;
        for (Order order : stale) {
            try {
                inventoryClient.release(order.getOrderRef());
                order.cancel();
                saveWithEvent(order);
                settled++;
                log.info("reconciled {} order {} -> CANCELLED", order.getStatus(), order.getOrderRef());
            } catch (RestClientException | CallNotPermittedException e) {
                log.warn("inventory still unavailable, order {} stays {}: {}", order.getOrderRef(), order.getStatus(), e.getMessage());
                break; // inventory is down; no point hammering it for the rest of the batch
            } catch (OptimisticLockingFailureException e) {
                // Expected with >1 pod (every replica runs the reconciler on the same stale rows — no
                // SKIP LOCKED here) or when the customer cancels concurrently: someone else settled
                // this order between our find and save. That is success, not failure — before Walk 53
                // this escaped, aborting the rest of the batch and this tick's gauge update with an
                // ERROR stacktrace for a normal race. The double release was already idempotent.
                log.info("order {} was settled concurrently (another pod or the customer), skipping", order.getOrderRef());
            }
        }
        return settled;
    }

    /**
     * CONFIRMED orders older than the grace period are a DIFFERENT kind of limbo: payment-service may
     * still be about to reply (its pod restarting, consumer lag), so auto-cancelling could cancel an
     * order that was in fact paid. We must NOT guess. Instead expose how many are stuck as a gauge; an
     * alert (OrdersStuckConfirmed) pages a human to look. Same idea for REFUND_PENDING stuck too long.
     */
    public long countStuckConfirmed(Duration grace) {
        return orderRepository.countByStatusInAndUpdatedAtBefore(
                List.of(OrderStatus.CONFIRMED, OrderStatus.REFUND_PENDING), Instant.now().minus(grace));
    }

    /**
     * Order row + outbox row in ONE short local transaction (transactional outbox pattern).
     * TransactionTemplate instead of @Transactional on purpose: calling an annotated method
     * from inside the same class bypasses the proxy, so it would silently run without a transaction.
     */
    Order saveWithEvent(Order order) {
        Order saved = transactionTemplate.execute(status -> {
            Order persisted = orderRepository.save(order);
            OrderEvent event = OrderEvent.from(persisted);
            outbox.publish(OrderEvent.TOPIC, "Order", persisted.getOrderRef(), event.type(), event);
            return persisted;
        });
        count(saved);
        return saved;
    }

    // orders_total{status="CONFIRMED"} etc. in Prometheus
    private void count(Order order) {
        meterRegistry.counter("orders", "status", order.getStatus().name()).increment();
    }
}
