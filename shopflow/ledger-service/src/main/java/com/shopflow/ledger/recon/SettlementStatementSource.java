package com.shopflow.ledger.recon;

import java.util.List;

/**
 * The seam to the external settlement truth — a PSP/bank statement (Stripe balance transactions,
 * a bank settlement file, an exchange's clearing report). An Anti-Corruption Layer boundary: the
 * recon domain speaks {@link SettlementLine}s, and an adapter translates whatever the external
 * format is (CSV, API, SWIFT MT940) into them, so a change in the external shape never leaks into
 * the ledger. Real adapters (CSV from S3, a PSP API) are a later increment; the default bean
 * returns nothing, which the scheduler reads as "no statement configured, skip".
 */
public interface SettlementStatementSource {

    /** The settlement lines to reconcile against the ledger (empty = no statement available). */
    List<SettlementLine> fetchStatement();
}
