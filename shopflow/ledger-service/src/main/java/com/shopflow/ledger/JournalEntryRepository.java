package com.shopflow.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shopflow.ledger.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /** Idempotency at the ledger boundary: a replayed source event finds its existing entry. */
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    /** All entries for a business reference (orderRef / trade id) — reconciliation + audit. */
    List<JournalEntry> findByReference(String reference);
}
