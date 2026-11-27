package com.shopflow.ledger;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.AccountType;
import com.shopflow.ledger.domain.DoubleEntry;
import com.shopflow.ledger.domain.FxConversion;
import com.shopflow.ledger.domain.FxRate;
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
     * The generic (REST) path books the entry as {@link JournalEntryStatus#INITIATED} — a manually
     * posted entry is not yet confirmed. The saga listener uses the overload below with
     * {@link JournalEntryStatus#CAPTURED}, because an entry posted from a {@code PaymentCaptured} event
     * records money that has ALREADY moved; reconciliation then drives it SETTLED -> RECONCILED (Walk 116).
     */
    public JournalEntry record(String idempotencyKey, String reference, String currency, List<PostingLine> lines) {
        return record(idempotencyKey, reference, currency, lines, JournalEntryStatus.INITIATED);
    }

    public JournalEntry record(String idempotencyKey, String reference, String currency,
                               List<PostingLine> lines, JournalEntryStatus initialStatus) {
        // 1. invariant first — an unbalanced request never reaches the database
        DoubleEntry.validate(currency, lines);

        // 2. idempotent replay fast-path (an optimization; the unique index is the real guarantee)
        var existing = journalEntries.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 3. entry + postings in ONE transaction, so the ledger is never half-posted
        try {
            return transactionTemplate.execute(status -> persist(idempotencyKey, reference, currency, lines, initialStatus));
        } catch (DataIntegrityViolationException raceLost) {
            // a concurrent request with the same key committed between our pre-check and our insert.
            // The failed transaction has fully rolled back; this re-fetch runs in a fresh one.
            log.info("idempotency race for key {}: returning the winner", idempotencyKey);
            return journalEntries.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raceLost);
        }
    }

    /** The atomic write. Runs inside the TransactionTemplate; any throw rolls the whole entry back. */
    private JournalEntry persist(String idempotencyKey, String reference, String currency,
                                 List<PostingLine> lines, JournalEntryStatus initialStatus) {
        JournalEntry entry = journalEntries.save(
                new JournalEntry(idempotencyKey, reference, currency, initialStatus));
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

    /** Both journal entries of one FX conversion, plus the amounts (base {@code sold}, quote {@code bought}). */
    public record FxPosting(JournalEntry sell, JournalEntry buy, Money sold, Money bought) {
    }

    /**
     * Book a cross-currency conversion durably: {@code amount} (in {@code rate.base()}) leaves
     * {@code fromAccount} and the converted quote amount lands in {@code toAccount}, as the TWO balanced
     * single-currency entries {@link FxConversion} produces (FX inc-4c). Both entries are written in ONE
     * transaction, so the conversion is all-or-nothing — there is no window where the base left but the
     * quote never arrived (the clearing accounts would otherwise be left lopsided).
     *
     * Idempotent on {@code idempotencyKey} (each leg keyed {@code :fx-sell}/{@code :fx-buy}); a replay
     * returns the same pair and never double-posts. Same NOT-@Transactional structure as {@link #record}
     * (Walk 99): the atomic write is in the TransactionTemplate, the unique-key race recovery is caught
     * outside it. The per-currency {@code FX_CLEARING:<ccy>} control accounts are ensured on first use —
     * they are DERIVED from the (FxRate-validated) currency, not user input, so there is no typo-absorbs-
     * money risk that the deliberate-creation rule guards business accounts against.
     */
    public FxPosting convertCurrency(String idempotencyKey, String reference,
                                     String fromAccount, String toAccount, Money amount, FxRate rate) {
        String baseClearing = "FX_CLEARING:" + rate.base();
        String quoteClearing = "FX_CLEARING:" + rate.quote();
        FxConversion.Result r = FxConversion.convert(fromAccount, toAccount, baseClearing, quoteClearing, amount, rate);

        // invariant first — both legs, before any write
        DoubleEntry.validate(rate.base(), r.sellLeg());
        DoubleEntry.validate(rate.quote(), r.buyLeg());

        String sellKey = idempotencyKey + ":fx-sell";
        String buyKey = idempotencyKey + ":fx-buy";
        var existingSell = journalEntries.findByIdempotencyKey(sellKey);
        var existingBuy = journalEntries.findByIdempotencyKey(buyKey);
        if (existingSell.isPresent() && existingBuy.isPresent()) {
            // Idempotent replay: return what was ACTUALLY booked, read back from the persisted legs — NOT
            // r.sold()/r.bought(), which are recomputed from THIS call's amount+rate. A replay carrying the
            // same key with a different body (a buggy client, a mutated retry) must still get the original
            // conversion back, the same way record() returns the persisted entry rather than re-deriving it.
            return new FxPosting(existingSell.get(), existingBuy.get(),
                    bookedAmount(existingSell.get()), bookedAmount(existingBuy.get()));
        }

        accounts.create(baseClearing, AccountType.ASSET, rate.base());   // idempotent; derived control accounts
        accounts.create(quoteClearing, AccountType.ASSET, rate.quote());

        try {
            return transactionTemplate.execute(status -> new FxPosting(
                    persist(sellKey, reference, rate.base(), r.sellLeg(), JournalEntryStatus.INITIATED),
                    persist(buyKey, reference, rate.quote(), r.buyLeg(), JournalEntryStatus.INITIATED),
                    r.sold(), r.bought()));
        } catch (DataIntegrityViolationException raceLost) {
            // a concurrent request with the same key won; return ITS booked amounts, read from the persisted
            // legs, for the same reason as the replay fast-path above (not this call's recomputed r).
            JournalEntry sell = journalEntries.findByIdempotencyKey(sellKey).orElseThrow(() -> raceLost);
            JournalEntry buy = journalEntries.findByIdempotencyKey(buyKey).orElseThrow(() -> raceLost);
            log.info("fx conversion idempotency race for key {}: returning the winners", idempotencyKey);
            return new FxPosting(sell, buy, bookedAmount(sell), bookedAmount(buy));
        }
    }

    /**
     * The amount actually booked for one FX leg, read back from its persisted postings. Both postings of a
     * single-currency leg carry the same magnitude and currency (e.g. the sell leg is CR fromAccount / DR
     * clearing, both = the base amount), so either one is the leg's amount. Used on the idempotent replay and
     * race-recovery paths so the returned amounts are always the persisted truth, never re-derived from a
     * (possibly different) replay request.
     */
    private Money bookedAmount(JournalEntry entry) {
        return postings.findByJournalEntryId(entry.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("entry " + entry.getId() + " has no postings"))
                .getAmount();
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
