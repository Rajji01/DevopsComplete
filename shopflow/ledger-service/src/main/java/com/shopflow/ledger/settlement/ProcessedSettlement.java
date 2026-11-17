package com.shopflow.ledger.settlement;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The durable, shared dedup record — one row per settled trade. This is what the broken consumer's
 * in-memory {@code HashSet} should always have been: it survives restarts, every pod sees it, and its
 * PRIMARY KEY turns "settle a trade at most once" into a guarantee the database enforces.
 *
 * Persistable with isNew()==true: insert-only rows, so save() does a plain INSERT (and a duplicate hits the
 * primary key) instead of merge()+SELECT.
 */
@Entity
@Table(name = "processed_settlement")
public class ProcessedSettlement implements Persistable<String> {

    @Id
    @Column(name = "trade_id", length = 64)
    private String tradeId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedSettlement() {
    }

    public ProcessedSettlement(String tradeId) {
        this.tradeId = tradeId;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() {
        return tradeId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
