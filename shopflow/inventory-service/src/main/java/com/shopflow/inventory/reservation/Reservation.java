package com.shopflow.inventory.reservation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per order. The unique order_ref makes reserve/release idempotent,
 * so order-service can safely retry after a timeout without double-reserving.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    public enum Status { RESERVED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false, unique = true, length = 64)
    private String orderRef;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() {
    }

    Reservation(String orderRef, String sku, int quantity) {
        this.orderRef = orderRef;
        this.sku = sku;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = Instant.now();
    }

    // NOTE: no in-memory status flip on purpose (Walk 55). The RESERVED -> RELEASED transition
    // happens through ReservationRepository.transition(), a conditional UPDATE, because this
    // entity has no @Version: a plain field write would let two concurrent releases both "win"
    // and put the stock back twice.

    public String getOrderRef() {
        return orderRef;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }
}
