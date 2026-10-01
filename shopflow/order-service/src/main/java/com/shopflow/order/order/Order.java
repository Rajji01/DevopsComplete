package com.shopflow.order.order;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders") // "order" is a reserved SQL word
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // public id shared with inventory-service; also the idempotency key for the reservation
    @Column(name = "order_ref", nullable = false, unique = true, length = 64)
    private String orderRef;

    // client-supplied Idempotency-Key header, so a retried POST does not create a second order
    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // optimistic locking: two concurrent updates of the same order -> one fails instead of overwriting
    @Version
    private long version;

    protected Order() {
    }

    static Order pending(String sku, int quantity, String idempotencyKey) {
        Order order = new Order();
        order.orderRef = UUID.randomUUID().toString();
        order.idempotencyKey = idempotencyKey;
        order.sku = sku;
        order.quantity = quantity;
        order.status = OrderStatus.PENDING;
        return order;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    void confirm() {
        status = OrderStatus.CONFIRMED;
    }

    void reject(String reason) {
        status = OrderStatus.REJECTED;
        failureReason = reason;
    }

    void fail(String reason) {
        status = OrderStatus.FAILED;
        failureReason = reason;
    }

    void cancel() {
        status = OrderStatus.CANCELLED;
    }

    boolean isCancellable() {
        return status == OrderStatus.CONFIRMED || status == OrderStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
