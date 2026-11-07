package com.shopflow.notification;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotent consumer: Kafka delivers at-least-once (the outbox relay can also re-send),
 * so every eventId is recorded in the same transaction as the side effect. A duplicate
 * hits the primary key and is skipped instead of sending a second email.
 * Persistable with isNew()==true: these rows are insert-only, so save() does a plain INSERT
 * instead of merge()+SELECT that an assigned @Id would otherwise trigger (Walk 38).
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "order_ref", nullable = false, length = 64)
    private String orderRef;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    ProcessedEvent(UUID eventId, String orderRef) {
        this.eventId = eventId;
        this.orderRef = orderRef;
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
