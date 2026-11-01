package com.shopflow.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Transactional outbox: the event is written in the SAME database transaction as the order,
 * so "order saved but event lost" (or the reverse) cannot happen. A relay publishes rows
 * to Kafka afterwards. This replaces the unsafe "save to DB, then call kafka.send()" dual write.
 *
 * Implements Persistable so save() does a plain INSERT, not merge(): with an app-assigned @Id,
 * Spring Data would otherwise treat the row as existing and issue a SELECT before every INSERT
 * (Walk 21). Outbox rows are only ever inserted (the relay flips published_at via dirty checking,
 * never save()), so isNew() is always true.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    // Kafka message key -> all events of one order land on the same partition, in order
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String topic, String aggregateType, String aggregateId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public String getTopic() {
        return topic;
    }

    void markPublished() {
        this.publishedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;   // outbox rows are only inserted, so always persist() — never merge()+SELECT
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
