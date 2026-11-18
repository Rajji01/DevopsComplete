package com.shopflow.ledger.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One business event recorded in the ledger (a payment capture, a refund, a fee). Immutable once
 * written except for its lifecycle {@code status}. The {@code idempotencyKey} (the source event id)
 * has a unique constraint, so a replayed event posts exactly once — the inbox/idempotency guarantee
 * at the ledger boundary. {@code reference} is the business key (e.g. the orderRef / trade id) that
 * reconciliation and position queries group by. {@code version} gives optimistic locking for the
 * status transitions.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JournalEntryStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected JournalEntry() {
    }

    public JournalEntry(String idempotencyKey, String reference, String currency, JournalEntryStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.reference = reference;
        this.currency = currency;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Guarded status transition: rejects an illegal move (see {@link JournalEntryStatus}). */
    public void transitionTo(JournalEntryStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal transition " + status + " -> " + next + " for entry " + reference);
        }
        this.status = next;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getReference() {
        return reference;
    }

    public JournalEntryStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
