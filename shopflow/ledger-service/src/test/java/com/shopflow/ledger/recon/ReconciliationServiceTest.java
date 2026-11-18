package com.shopflow.ledger.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.JournalEntryRepository;
import com.shopflow.ledger.LedgerService;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;
import com.shopflow.ledger.recon.ReconciliationReport.Bucket;
import com.shopflow.ledger.saga.LedgerAccounts;

/**
 * The post-trade reconciliation control: the ledger's net cash per reference vs the external
 * settlement statement. CASH + SALES_REVENUE are seeded at startup; @Transactional isolates each
 * test's postings so one run sees only its own references.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReconciliationServiceTest {

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JournalEntryRepository journalEntries;

    // a capture posts a CAPTURED entry (money has moved) — reconciliation closes it to RECONCILED
    private void postCapture(String reference, long minor) {
        ledger.record(UUID.randomUUID().toString(), reference, LedgerAccounts.CURRENCY, List.of(
                new PostingLine(LedgerAccounts.CASH, PostingDirection.DEBIT, Money.of(minor, LedgerAccounts.CURRENCY)),
                new PostingLine(LedgerAccounts.SALES_REVENUE, PostingDirection.CREDIT, Money.of(minor, LedgerAccounts.CURRENCY))),
                JournalEntryStatus.CAPTURED);
    }

    private void postRefund(String reference, long minor) {
        ledger.record(UUID.randomUUID().toString(), reference, LedgerAccounts.CURRENCY, List.of(
                new PostingLine(LedgerAccounts.SALES_REVENUE, PostingDirection.DEBIT, Money.of(minor, LedgerAccounts.CURRENCY)),
                new PostingLine(LedgerAccounts.CASH, PostingDirection.CREDIT, Money.of(minor, LedgerAccounts.CURRENCY))),
                JournalEntryStatus.CAPTURED);
    }

    private static SettlementLine line(String reference, long minor) {
        return new SettlementLine(reference, minor, LedgerAccounts.CURRENCY);
    }

    @Test
    void equalNetsTieOut() {
        postCapture("order-A", 50000);

        var report = reconciliation.reconcile(List.of(line("order-A", 50000)));

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.exceptions()).isZero();
    }

    @Test
    void differentAmountIsAMismatchException() {
        postCapture("order-B", 50000);

        var report = reconciliation.reconcile(List.of(line("order-B", 40000)));

        assertThat(report.matched()).isZero();
        assertThat(report.discrepancies()).singleElement()
                .satisfies(d -> {
                    assertThat(d.reference()).isEqualTo("order-B");
                    assertThat(d.bucket()).isEqualTo(Bucket.AMOUNT_MISMATCH);
                    assertThat(d.ledgerMinor()).isEqualTo(50000);
                    assertThat(d.statementMinor()).isEqualTo(40000);
                });
    }

    @Test
    void statementSettlementWeNeverBookedIsMissingInLedger() {
        // nothing posted for order-C, but the PSP says it settled
        var report = reconciliation.reconcile(List.of(line("order-C", 30000)));

        assertThat(report.discrepancies()).singleElement()
                .satisfies(d -> assertThat(d.bucket()).isEqualTo(Bucket.MISSING_IN_LEDGER));
    }

    @Test
    void bookedEntryTheStatementDoesNotShowIsMissingInStatement() {
        postCapture("order-D", 20000);

        var report = reconciliation.reconcile(List.of());

        assertThat(report.discrepancies()).singleElement()
                .satisfies(d -> assertThat(d.bucket()).isEqualTo(Bucket.MISSING_IN_STATEMENT));
    }

    @Test
    void fullyRefundedReferenceNetsToZeroAndIsNotAnException() {
        postCapture("order-E", 50000);
        postRefund("order-E", 50000);   // net cash movement for order-E is zero

        var report = reconciliation.reconcile(List.of());

        // nothing to settle, so it is neither matched nor an exception
        assertThat(report.exceptions()).isZero();
        assertThat(report.matched()).isZero();
    }

    @Test
    void matchedReferenceIsAdvancedToReconciled() {
        postCapture("order-F", 50000);
        assertThat(journalEntries.findByReference("order-F")).singleElement()
                .extracting(e -> e.getStatus()).isEqualTo(JournalEntryStatus.CAPTURED);

        reconciliation.reconcile(List.of(line("order-F", 50000)));   // ties out -> settle + reconcile

        assertThat(journalEntries.findByReference("order-F")).singleElement()
                .extracting(e -> e.getStatus()).isEqualTo(JournalEntryStatus.RECONCILED);
    }

    @Test
    void reconcileIsIdempotentForAnAlreadyReconciledEntry() {
        postCapture("order-G", 50000);
        reconciliation.reconcile(List.of(line("order-G", 50000)));   // -> RECONCILED

        // a second run re-sees the same match; it must NOT throw an illegal-transition on the terminal entry
        reconciliation.reconcile(List.of(line("order-G", 50000)));

        assertThat(journalEntries.findByReference("order-G")).singleElement()
                .extracting(e -> e.getStatus()).isEqualTo(JournalEntryStatus.RECONCILED);
    }

    @Test
    void mismatchedReferenceIsNotSettledAndStaysCaptured() {
        postCapture("order-H", 50000);

        reconciliation.reconcile(List.of(line("order-H", 40000)));   // AMOUNT_MISMATCH, not matched

        // an exception reference must never be quietly closed — it stays CAPTURED for a human to resolve
        assertThat(journalEntries.findByReference("order-H")).singleElement()
                .extracting(e -> e.getStatus()).isEqualTo(JournalEntryStatus.CAPTURED);
    }
}
