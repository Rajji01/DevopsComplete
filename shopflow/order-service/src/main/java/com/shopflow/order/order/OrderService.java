package com.shopflow.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

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

    public PlacedOrder placeOrder(String sku, int quantity, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("idempotent replay for key {}, returning order {}", idempotencyKey, existing.get().getId());
                return new PlacedOrder(existing.get(), false);
            }
        }

        Order order = orderRepository.save(Order.pending(sku, quantity, idempotencyKey));
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

    public Order get(long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public Page<Order> list(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    /** Compensation step of the saga: give the reserved stock back. */
    public Order cancel(long id) {
        Order order = get(id);
        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(id, order.getStatus());
        }
        inventoryClient.release(order.getOrderRef());
        order.cancel();
        return saveWithEvent(order);
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
