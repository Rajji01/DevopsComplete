package com.shopflow.ledger;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.DoubleEntry;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.Posting;
import com.shopflow.ledger.domain.PostingLine;

/**
 * Records money movements as balanced journal entries, idempotently (increment 1 — persistence).
 *
 * The entry + all its postings are written in ONE transaction, so the ledger is never left with a
 * half-posted (unbalanced) entry. Idempotency is enforced by the unique index on idempotency_key:
 * a replayed source event finds its existing entry and posts nothing more. Outbound events
 * (LedgerEntryRecorded via the shared outbox) and the inbound saga consumer are increment 2.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JournalEntryRepository journalEntries;
    private final AccountService accounts;
    private final PostingRepository postings;

    public LedgerService(JournalEntryRepository journalEntries, AccountService accounts, PostingRepository postings) {
        this.journalEntries = journalEntries;
        this.accounts = accounts;
        this.postings = postings;
    }

    /**
     * Post a balanced set of legs for one business event, exactly once per {@code idempotencyKey}.
     *
     * @param idempotencyKey the source event id — a replay returns the already-recorded entry
     * @param reference      business key (orderRef / trade id)
     * @param currency       the entry currency; every leg and every referenced account must match
     * @param lines          the debit/credit legs (must balance)
     */
    @Transactional
    public JournalEntry record(String idempotencyKey, String reference, String currency, List<PostingLine> lines) {
        // 1. invariant first — an unbalanced request never reaches the database
        DoubleEntry.validate(currency, lines);

        // 2. idempotent replay fast-path
        var existing = journalEntries.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 3. persist the entry; the unique idempotency_key is the real guard against a concurrent
        //    double-post that raced past the pre-check above (same recovery as order-service, Walk 45)
        JournalEntry entry;
        try {
            entry = journalEntries.save(new JournalEntry(idempotencyKey, reference, currency, JournalEntryStatus.INITIATED));
        } catch (DataIntegrityViolationException raceLost) {
            var winner = journalEntries.findByIdempotencyKey(idempotencyKey);
            if (winner.isEmpty()) {
                throw raceLost; // some other constraint, not the idempotency race
            }
            log.info("idempotency race for key {}: returning winner entry {}", idempotencyKey, winner.get().getId());
            return winner.get();
        }

        // 4. one immutable posting per leg, against a real account of the same currency
        for (PostingLine line : lines) {
            Account account = accounts.require(line.accountCode());
            if (!account.getCurrency().equals(currency)) {
                throw new IllegalArgumentException(
                        "account " + account.getCode() + " is " + account.getCurrency() + ", entry is " + currency);
            }
            postings.save(new Posting(entry.getId(), account.getId(), line.direction(), line.amount()));
        }
        log.info("recorded entry {} (ref {}) with {} postings", entry.getId(), reference, lines.size());
        return entry;
    }

    /**
     * An account's balance, DERIVED from its postings (signed sum), never stored. For a high-volume
     * account this would be snapshotted periodically and summed from the snapshot forward; at this
     * scale the full sum keeps the "balance is just the postings" property honest and visible.
     */
    @Transactional(readOnly = true)
    public Money balanceOf(String accountCode) {
        Account account = accounts.require(accountCode);
        long signedMinor = postings.findByAccountId(account.getId()).stream()
                .mapToLong(p -> p.getDirection().signed(p.getAmountMinor()))
                .sum();
        return new Money(signedMinor, account.getCurrency());
    }
}
