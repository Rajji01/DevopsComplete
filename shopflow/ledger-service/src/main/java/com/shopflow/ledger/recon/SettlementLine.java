package com.shopflow.ledger.recon;

/**
 * One line of the external settlement statement (the PSP / bank's record of money actually moved),
 * keyed by the same business {@code reference} the ledger entries carry (the orderRef / trade id).
 * {@code amountMinor} is the NET the processor says it settled for that reference in minor units
 * (a capture positive, a refund reducing it) — reconciliation compares it against the ledger's own
 * net cash movement for the same reference. Signed: a net refund can be negative.
 */
public record SettlementLine(String reference, long amountMinor, String currency) {

    public SettlementLine {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference required");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("ISO-4217 3-letter currency required, got: " + currency);
        }
    }
}
