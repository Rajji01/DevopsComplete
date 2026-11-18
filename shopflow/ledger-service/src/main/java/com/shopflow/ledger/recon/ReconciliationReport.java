package com.shopflow.ledger.recon;

import java.util.List;

/**
 * The result of one reconciliation run: how many references tied out, and every one that did not,
 * bucketed. The buckets are the classic recon exception types — the point of reconciliation is not
 * "is the number right" but "WHICH references disagree and in which direction", because each bucket
 * is a different operational action (chase the PSP, chase our own booking, or investigate a
 * partial/wrong amount).
 */
public record ReconciliationReport(int matched, List<Discrepancy> discrepancies) {

    public enum Bucket {
        /** The statement shows a settlement the ledger never booked — money moved, we have no entry. */
        MISSING_IN_LEDGER,
        /** The ledger booked an entry the statement does not show — we booked something unsettled. */
        MISSING_IN_STATEMENT,
        /** Both sides have the reference but the net amounts differ — a partial/duplicate/wrong amount. */
        AMOUNT_MISMATCH
    }

    public record Discrepancy(String reference, Bucket bucket, long ledgerMinor, long statementMinor) {
    }

    /** References present on only one side (money unaccounted for on the other). */
    public long unmatched() {
        return discrepancies.stream().filter(d -> d.bucket() != Bucket.AMOUNT_MISMATCH).count();
    }

    /** References present on both sides but with different net amounts. */
    public long mismatched() {
        return discrepancies.stream().filter(d -> d.bucket() == Bucket.AMOUNT_MISMATCH).count();
    }

    /** Every reference that did not tie out — the exception queue a human works. */
    public int exceptions() {
        return discrepancies.size();
    }
}
