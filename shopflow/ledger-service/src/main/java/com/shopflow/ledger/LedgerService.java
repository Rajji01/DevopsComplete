package com.shopflow.ledger;

import java.util.List;

import org.springframework.stereotype.Service;

import com.shopflow.ledger.domain.DoubleEntry;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.PostingLine;

/**
 * Records money movements as balanced journal entries, idempotently.
 *
 * Skeleton (Walk 96): the double-entry invariant check and the idempotency lookup are wired; the
 * actual persistence of the entry + postings, account resolution, and the outbound
 * LedgerEntryRecorded event are increment 1. The stub throws rather than silently no-op'ing, so a
 * caller can never mistake "not implemented yet" for "recorded".
 */
@Service
public class LedgerService {

    private final JournalEntryRepository journalEntries;
    private final AccountRepository accounts;
    private final PostingRepository postings;

    public LedgerService(JournalEntryRepository journalEntries, AccountRepository accounts, PostingRepository postings) {
        this.journalEntries = journalEntries;
        this.accounts = accounts;
        this.postings = postings;
    }

    /**
     * Post a balanced set of legs for one business event, exactly once per {@code idempotencyKey}.
     *
     * @param idempotencyKey the source event id — a replay returns the already-recorded entry
     * @param reference      business key (orderRef / trade id)
     * @param currency       the entry currency; every leg must match
     * @param lines          the debit/credit legs (must balance)
     */
    public JournalEntry record(String idempotencyKey, String reference, String currency, List<PostingLine> lines) {
        // 1. invariant first — an unbalanced request never reaches the database (real, tested)
        DoubleEntry.validate(currency, lines);

        // 2. idempotent replay guard (real)
        var existing = journalEntries.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 3. TODO(increment 1): in ONE transaction — resolve/create each account by code,
        //    persist the JournalEntry (status INITIATED) + its Postings, then publish a
        //    LedgerEntryRecorded event through the shared transactional outbox. Account creation
        //    races are handled the Walk-45 way (unique code + catch + re-fetch the winner).
        throw new UnsupportedOperationException(
                "ledger persistence is increment 1 — the skeleton validates the double-entry invariant "
                        + "and the idempotency guard only. reference=" + reference + " status=" + JournalEntryStatus.INITIATED);
    }
}
