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

import com.shopflow.ledger.AccountService;
import com.shopflow.ledger.JournalEntryRepository;
import com.shopflow.ledger.PostingRepository;
import com.shopflow.ledger.domain.Account;
import com.shopflow.ledger.domain.JournalEntry;
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

    // last-run gauges (updated each reconcile); 0 at startup until the first run
    private final AtomicInteger matchedGauge = new AtomicInteger();
    private final AtomicInteger unmatchedGauge = new AtomicInteger();
    private final AtomicInteger mismatchGauge = new AtomicInteger();

    public ReconciliationService(PostingRepository postings, JournalEntryRepository journalEntries,
                                 AccountService accounts, SettlementStatementSource statementSource,
                                 MeterRegistry meterRegistry) {
        this.postings = postings;
        this.journalEntries = journalEntries;
        this.accounts = accounts;
        this.statementSource = statementSource;
        Gauge.builder("ledger.recon.matched", matchedGauge, AtomicInteger::get)
                .description("References that tied out on the last reconciliation run").register(meterRegistry);
        Gauge.builder("ledger.recon.unmatched", unmatchedGauge, AtomicInteger::get)
                .description("References present on only one side (missing in ledger or statement)").register(meterRegistry);
        Gauge.builder("ledger.recon.mismatch", mismatchGauge, AtomicInteger::get)
                .description("References present on both sides with different net amounts").register(meterRegistry);
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
     * Pure reconciliation: compare the ledger's net CASH movement per reference against the
     * statement's net per reference, bucket every disagreement, and publish the gauges.
     *
     * Deliberately NOT @Transactional (Walk 106): the only caller inside this bean is
     * {@link #scheduledReconcile()}, and a self-invocation bypasses the Spring proxy — so a
     * @Transactional here would silently do nothing on the scheduled path (it would appear to work
     * only when called directly, e.g. from a test, which masks the gap). The reads need no
     * transaction anyway: they are committed-read batch queries with no write and no lazy loading,
     * and every CASH posting's entry is committed in the same unit as the posting, so the two reads
     * can never see a posting whose entry is missing. Wrapping the scheduled path in a transaction
     * would also risk pinning a DB connection across the (potentially remote) statement fetch.
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
        return report;
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
