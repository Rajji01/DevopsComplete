package com.shopflow.order.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.shopflow.order.common.InventoryUnavailableException;
import com.shopflow.order.common.OrderNotCancellableException;
import com.shopflow.order.common.OrderNotFoundException;
import com.shopflow.order.inventory.InventoryClient;
import com.shopflow.order.inventory.InventoryRejectedException;

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
    private final InventoryClient inventoryClient;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orderRepository, InventoryClient inventoryClient, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.meterRegistry = meterRegistry;
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
            orderRepository.save(order);
            count(order);
            throw new InventoryUnavailableException(order.getId(), e);
        }

        order = orderRepository.save(order);
        count(order);
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
        order = orderRepository.save(order);
        count(order);
        return order;
    }

    // orders_total{status="CONFIRMED"} etc. in Prometheus
    private void count(Order order) {
        meterRegistry.counter("orders", "status", order.getStatus().name()).increment();
    }
}
