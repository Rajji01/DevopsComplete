package com.shopflow.order.order;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.shopflow.order.outbox.OutboxEvent;
import com.shopflow.order.outbox.OutboxRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final OutboxRepository outboxRepository;
    private final InventoryClient inventoryClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository, OutboxRepository outboxRepository,
                        InventoryClient inventoryClient, MeterRegistry meterRegistry,
                        TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.inventoryClient = inventoryClient;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
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

        Order order = orderRepository.save(Order.pending(sku, quantity, idempotencyKey, caller.customerId()));
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

    /** Compensation step of the saga: give the reserved stock back. */
    public Order cancel(long id, Caller caller) {
        Order order = get(id, caller);
        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(id, order.getStatus());
        }
        inventoryClient.release(order.getOrderRef());
        order.cancel();
        return saveWithEvent(order);
    }

    /**
     * FAILED = we never learned whether inventory reserved the stock (timeout, breaker open).
     * After a grace period, give up: release is idempotent (a no-op if nothing was reserved),
     * so calling it is always safe, and the customer gets a definite CANCELLED instead of limbo.
     * Runs on a schedule (OrderReconciler); returns how many orders it settled.
     */
    public int reconcileFailedOrders(Duration grace) {
        var stale = orderRepository.findTop100ByStatusAndUpdatedAtBefore(OrderStatus.FAILED, Instant.now().minus(grace));
        int settled = 0;
        for (Order order : stale) {
            try {
                inventoryClient.release(order.getOrderRef());
                order.cancel();
                saveWithEvent(order);
                settled++;
                log.info("reconciled FAILED order {} -> CANCELLED", order.getOrderRef());
            } catch (RestClientException | CallNotPermittedException e) {
                log.warn("inventory still unavailable, order {} stays FAILED: {}", order.getOrderRef(), e.getMessage());
                break; // inventory is down; no point hammering it for the rest of the batch
            }
        }
        return settled;
    }

    /**
     * Order row + outbox row in ONE short local transaction (transactional outbox pattern).
     * TransactionTemplate instead of @Transactional on purpose: calling an annotated method
     * from inside the same class bypasses the proxy, so it would silently run without a transaction.
     */
    private Order saveWithEvent(Order order) {
        Order saved = transactionTemplate.execute(status -> {
            Order persisted = orderRepository.save(order);
            OrderEvent event = OrderEvent.from(persisted);
            outboxRepository.save(new OutboxEvent("Order", persisted.getOrderRef(), event.type(), toJson(event)));
            return persisted;
        });
        count(saved);
        return saved;
    }

    private String toJson(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + event, e);
        }
    }

    // orders_total{status="CONFIRMED"} etc. in Prometheus
    private void count(Order order) {
        meterRegistry.counter("orders", "status", order.getStatus().name()).increment();
    }
}
