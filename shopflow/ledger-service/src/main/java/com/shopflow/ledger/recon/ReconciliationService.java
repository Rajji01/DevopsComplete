package com.shopflow.ledger.recon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.shopflow.ledger.AccountService;
import com.shopflow.ledger.JournalEntryRepository;
import com.shopflow.ledger.PostingRepository;
import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.JournalEntry;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Posting;
import com.shopflow.ledger.recon.ReconciliationReport.Bucket;
import com.shopflow.ledger.recon.ReconciliationReport.Discrepancy;
import com.shopflow.ledger.saga.LedgerAccounts;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reconciles the ledger's own record of money moved against the external settlement statement
 * (the PSP/bank's record), per business reference — the post-trade control that answers "do our
 * books tie out with what actually settled?". The ledger's net cash per reference is DERIVED from
 * its postings (no stored balance to drift); the statement is read through {@link
 * SettlementStatementSource}. Every reference that does not tie out lands in a bucket and raises a
 * metric the {@code LedgerReconExceptions} alert watches — a mismatch is never silently absorbed.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PostingRepository postings;
    private final JournalEntryRepository journalEntries;
    private final AccountService accounts;
    private final SettlementStatementSource statementSource;
    private final TransactionTemplate transactionTemplate;

    // last-run gauges (updated each reconcile); 0 at startup until the first run
    private final AtomicInteger matchedGauge = new AtomicInteger();
    private final AtomicInteger unmatchedGauge = new AtomicInteger();
    private final AtomicInteger mismatchGauge = new AtomicInteger();
    private final AtomicInteger reconciledGauge = new AtomicInteger();

    public ReconciliationService(PostingRepository postings, JournalEntryRepository journalEntries,
                                 AccountService accounts, SettlementStatementSource statementSource,
                                 TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.postings = postings;
        this.journalEntries = journalEntries;
        this.accounts = accounts;
        this.statementSource = statementSource;
        this.transactionTemplate = transactionTemplate;
        Gauge.builder("ledger.recon.matched", matchedGauge, AtomicInteger::get)
                .description("References that tied out on the last reconciliation run").register(meterRegistry);
        Gauge.builder("ledger.recon.unmatched", unmatchedGauge, AtomicInteger::get)
                .description("References present on only one side (missing in ledger or statement)").register(meterRegistry);
        Gauge.builder("ledger.recon.mismatch", mismatchGauge, AtomicInteger::get)
                .description("References present on both sides with different net amounts").register(meterRegistry);
        Gauge.builder("ledger.recon.reconciled", reconciledGauge, AtomicInteger::get)
                .description("Journal entries advanced to RECONCILED on the last reconciliation run").register(meterRegistry);
    }

    /** Daily control run against the configured statement source. */
    @Scheduled(cron = "${ledger.recon.cron:0 0 4 * * *}")   // 04:00 UTC daily
    public void scheduledReconcile() {
        List<SettlementLine> statement = statementSource.fetchStatement();
        if (statement.isEmpty()) {
            // no feed configured yet: reconciling against nothing would flag every booked entry as
            // MISSING_IN_STATEMENT, which is noise, not a control signal. Skip until an adapter exists.
            log.info("reconciliation skipped: no settlement statement available");
            return;
        }
        ReconciliationReport report = reconcile(statement);
        if (report.exceptions() == 0) {
            log.info("reconciliation clean: {} references tied out", report.matched());
        } else {
            log.warn("reconciliation found {} exception(s): {} unmatched, {} mismatched (matched {})",
                    report.exceptions(), report.unmatched(), report.mismatched(), report.matched());
            report.discrepancies().forEach(d -> log.warn("  recon {} [{}] ledger={} statement={}",
                    d.reference(), d.bucket(), d.ledgerMinor(), d.statementMinor()));
        }
    }

    /**
     * Reconcile, then settle. First compare the ledger's net CASH movement per reference against the
     * statement's net per reference and bucket every disagreement (the read-only control); then, for
     * every reference that tied out, advance its journal entries CAPTURED -> SETTLED -> RECONCILED —
     * the money both settled (it is on the statement) and ties out, so the lifecycle closes (Walk 116).
     * Mismatched / unmatched references are NOT advanced: they stay CAPTURED, flagged by the exception
     * buckets and the {@code LedgerReconExceptions} alert, until a human resolves them.
     *
     * The comparison reads need no transaction (committed-read batch queries, no lazy loading; a CASH
     * posting's entry is committed in the same unit as the posting, so neither read sees a half-entry).
     * The settlement WRITES do need one, but this method is still deliberately NOT @Transactional
     * (Walk 106): its only in-bean caller is {@link #scheduledReconcile()}, and that self-invocation
     * bypasses the Spring proxy, so a @Transactional here would silently run with NO transaction on the
     * scheduled path (appearing to work only from a direct test call — the exact trap). Instead the
     * writes run in an explicit {@link TransactionTemplate}, which is proxy-independent and so behaves
     * identically on the scheduled and the test path. Keeping the (possibly remote) statement fetch and
     * the comparison OUT of that transaction also avoids pinning a DB connection across them.
     */
    public ReconciliationReport reconcile(List<SettlementLine> statement) {
        Map<String, Long> ledgerNet = ledgerNetCashByReference();
        Map<String, Long> statementNet = new HashMap<>();
        for (SettlementLine line : statement) {
            statementNet.merge(line.reference(), line.amountMinor(), Long::sum);
        }
        // a net-zero reference (a capture fully reversed by a refund) is nothing to settle — drop it
        // from both sides so it is neither a false "unmatched" nor counted as matched.
        ledgerNet.values().removeIf(v -> v == 0);
        statementNet.values().removeIf(v -> v == 0);

        int matched = 0;
        List<String> matchedReferences = new ArrayList<>();
        List<Discrepancy> discrepancies = new ArrayList<>();
        Set<String> references = new TreeSet<>();
        references.addAll(ledgerNet.keySet());
        references.addAll(statementNet.keySet());

        for (String reference : references) {
            Long ledger = ledgerNet.get(reference);
            Long stmt = statementNet.get(reference);
            long ledgerMinor = ledger == null ? 0L : ledger;
            long statementMinor = stmt == null ? 0L : stmt;
            if (ledger != null && stmt != null) {
                if (ledgerMinor == statementMinor) {
                    matched++;
                    matchedReferences.add(reference);
                } else {
                    discrepancies.add(new Discrepancy(reference, Bucket.AMOUNT_MISMATCH, ledgerMinor, statementMinor));
                }
            } else if (stmt != null) {
                discrepancies.add(new Discrepancy(reference, Bucket.MISSING_IN_LEDGER, ledgerMinor, statementMinor));
            } else {
                discrepancies.add(new Discrepancy(reference, Bucket.MISSING_IN_STATEMENT, ledgerMinor, statementMinor));
            }
        }

        ReconciliationReport report = new ReconciliationReport(matched, discrepancies);
        matchedGauge.set(report.matched());
        unmatchedGauge.set((int) report.unmatched());
        mismatchGauge.set((int) report.mismatched());

        reconciledGauge.set(settle(matchedReferences));
        return report;
    }

    /**
     * Close the lifecycle for every tied-out reference: advance its entries CAPTURED -> SETTLED ->
     * RECONCILED in one transaction. Idempotent — an entry a prior run already took to RECONCILED is
     * skipped (daily runs re-see yesterday's matches), as is any entry not yet CAPTURED (a raw
     * INITIATED/AUTHORIZED entry that happens to match is not money that moved, so it is not settled
     * here). @Version on JournalEntry guards a concurrent run. Returns how many entries were advanced.
     */
    private int settle(List<String> matchedReferences) {
        if (matchedReferences.isEmpty()) {
            return 0;
        }
        Integer advanced = transactionTemplate.execute(tx -> {
            int count = 0;
            for (String reference : matchedReferences) {
                for (JournalEntry entry : journalEntries.findByReference(reference)) {
                    if (entry.getStatus() == JournalEntryStatus.CAPTURED) {
                        entry.transitionTo(JournalEntryStatus.SETTLED);
                        entry.transitionTo(JournalEntryStatus.RECONCILED);
                        journalEntries.save(entry);
                        count++;
                    } else if (entry.getStatus() == JournalEntryStatus.SETTLED) {
                        entry.transitionTo(JournalEntryStatus.RECONCILED);
                        journalEntries.save(entry);
                        count++;
                    }
                    // RECONCILED (already done, prior run) / INITIATED / AUTHORIZED / REVERSED / FAILED:
                    // nothing to advance here.
                }
            }
            return count;
        });
        if (advanced > 0) {
            log.info("reconciliation settled {} journal entr{} across {} reference(s)",
                    advanced, advanced == 1 ? "y" : "ies", matchedReferences.size());
        }
        return advanced == null ? 0 : advanced;
    }

    /**
     * The ledger's net cash movement per reference, derived from the CASH account's postings
     * (debits positive, credits negative). Pure Java grouping — at reconciliation volume this is a
     * batch read, and it keeps the "balance is just the signed sum of postings" property visible
     * rather than hiding it in a SQL aggregate.
     */
    private Map<String, Long> ledgerNetCashByReference() {
        Account cash = accounts.require(LedgerAccounts.CASH);
        List<Posting> cashPostings = postings.findByAccountId(cash.getId());
        Set<Long> entryIds = cashPostings.stream().map(Posting::getJournalEntryId).collect(Collectors.toSet());
        Map<Long, String> referenceByEntry = journalEntries.findAllById(entryIds).stream()
                .collect(Collectors.toMap(JournalEntry::getId, JournalEntry::getReference));

        Map<String, Long> net = new HashMap<>();
        for (Posting p : cashPostings) {
            String reference = referenceByEntry.get(p.getJournalEntryId());
            if (reference != null) {
                net.merge(reference, p.getDirection().signed(p.getAmountMinor()), Long::sum);
            }
        }
        return net;
    }
}
