package com.shopflow.ledger;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.DoubleEntry;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.Posting;
import com.shopflow.ledger.domain.PostingLine;

/**
 * Records money movements as balanced journal entries, idempotently (increment 1).
 *
 * record() is deliberately NOT annotated @Transactional (Walk 99). The atomic write of the entry +
 * all its postings happens inside a TransactionTemplate; the idempotency-race recovery — catching
 * the unique-key violation of a concurrent double-post and returning the winner — MUST run OUTSIDE
 * that transaction. A @Transactional method that catches its own DataIntegrityViolationException
 * cannot recover: the exception has already marked the transaction rollback-only, so the method's
 * commit would throw UnexpectedRollbackException and the "recovery" never reaches the caller. This
 * is the exact structure OrderService.placeOrder uses, and the exact trap the first draft fell into.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final JournalEntryRepository journalEntries;
    private final AccountService accounts;
    private final PostingRepository postings;
    private final TransactionTemplate transactionTemplate;

    public LedgerService(JournalEntryRepository journalEntries, AccountService accounts,
                         PostingRepository postings, TransactionTemplate transactionTemplate) {
        this.journalEntries = journalEntries;
        this.accounts = accounts;
        this.postings = postings;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Post a balanced set of legs for one business event, exactly once per {@code idempotencyKey}.
     */
    public JournalEntry record(String idempotencyKey, String reference, String currency, List<PostingLine> lines) {
        // 1. invariant first — an unbalanced request never reaches the database
        DoubleEntry.validate(currency, lines);

        // 2. idempotent replay fast-path (an optimization; the unique index is the real guarantee)
        var existing = journalEntries.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 3. entry + postings in ONE transaction, so the ledger is never half-posted
        try {
            return transactionTemplate.execute(status -> persist(idempotencyKey, reference, currency, lines));
        } catch (DataIntegrityViolationException raceLost) {
            // a concurrent request with the same key committed between our pre-check and our insert.
            // The failed transaction has fully rolled back; this re-fetch runs in a fresh one.
            log.info("idempotency race for key {}: returning the winner", idempotencyKey);
            return journalEntries.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raceLost);
        }
    }

    /** The atomic write. Runs inside the TransactionTemplate; any throw rolls the whole entry back. */
    private JournalEntry persist(String idempotencyKey, String reference, String currency, List<PostingLine> lines) {
        JournalEntry entry = journalEntries.save(
                new JournalEntry(idempotencyKey, reference, currency, JournalEntryStatus.INITIATED));
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
