package com.shopflow.ledger.domain;

import java.util.List;

/**
 * The one invariant that makes a ledger a ledger: every journal entry's debits equal its credits.
 * Pure and side-effect free, so it is cheap to test exhaustively and impossible to bypass — the
 * persistence layer (increment 1) MUST call this before writing, and the saga consumer (increment 2)
 * before posting. If this never throws, the sum of ALL balances in the system is always zero:
 * money is only ever moved, never created or destroyed — the property reconciliation relies on.
 */
public final class DoubleEntry {

    private DoubleEntry() {
    }

    /**
     * @throws UnbalancedEntryException if fewer than two legs, any leg's currency differs from the
     *         entry currency, or the signed sum of the legs is non-zero.
     */
    public static void validate(String entryCurrency, List<PostingLine> lines) {
        if (lines == null || lines.size() < 2) {
            throw new UnbalancedEntryException("a journal entry needs at least two postings (a debit and a credit)");
        }
        long signedSum = 0;
        for (PostingLine line : lines) {
            if (!line.amount().currency().equals(entryCurrency)) {
                // mixed-currency entries need an explicit FX conversion leg — that is increment 4
                throw new UnbalancedEntryException(
                        "posting currency " + line.amount().currency() + " != entry currency " + entryCurrency);
            }
            signedSum += line.direction().signed(line.amount().minorUnits());
        }
        if (signedSum != 0) {
            throw new UnbalancedEntryException("debits != credits (signed sum = " + signedSum + " minor units)");
        }
    }
}
