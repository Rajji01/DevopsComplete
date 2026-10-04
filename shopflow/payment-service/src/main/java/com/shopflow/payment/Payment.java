package com.shopflow.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One payment per order. The primary key is the eventId of the OrderConfirmed event that
 * triggered it, which makes the consumer idempotent: a redelivered event hits the same row.
 */
@Entity
@Table(name = "payment")
public class Payment {

    public enum Status { CAPTURED, FAILED }

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "order_ref", nullable = false, unique = true, length = 64)
    private String orderRef;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    Payment(UUID eventId, String orderRef, BigDecimal amount, Status status, String failureReason) {
        this.eventId = eventId;
        this.orderRef = orderRef;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
    }

    public String getOrderRef() {
        return orderRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
