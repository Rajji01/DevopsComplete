package com.shopflow.order.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotent consumer bookkeeping for payment events (same pattern as notification-service).
 * Persistable with isNew()==true so save() does a plain INSERT, not merge()+SELECT (Walk 38;
 * these rows are only ever inserted — an assigned @Id otherwise makes Spring Data treat them as
 * existing). A duplicate eventId still fails on the primary key, which is the idempotency guard.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
