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

/**
 * One immutable, append-only leg of a journal entry: a debit or credit of a positive minor-unit
 * amount against one account. There is deliberately no setter and no update path — a correction is
 * a new reversing posting, never an edit, so the log stays an auditable history. Carries its own
 * {@code currency} (equal to the entry's) so a single-account balance query never has to join.
 */
@Entity
@Table(name = "posting")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Posting() {
    }

    public Posting(Long journalEntryId, Long accountId, PostingDirection direction, Money amount) {
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amount.minorUnits();
        this.currency = amount.currency();
    }

    public Long getId() {
        return id;
    }

    public Long getJournalEntryId() {
        return journalEntryId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Money getAmount() {
        return new Money(amountMinor, currency);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
