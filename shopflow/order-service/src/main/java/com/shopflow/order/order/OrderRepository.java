package com.shopflow.order.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Page<Order> findByCustomerId(String customerId, Pageable pageable);

    // reconciliation: orders whose inventory outcome is unknown and old enough to give up on
    List<Order> findTop100ByStatusAndUpdatedAtBefore(OrderStatus status, Instant before);
}
