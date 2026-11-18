package com.shopflow.ledger.domain;

/**
 * One leg of a journal entry is either a DEBIT or a CREDIT. Amounts are always stored positive;
 * the direction carries the sign. A journal entry is balanced iff the signed sum of its legs is
 * zero — so we define DEBIT as +, CREDIT as - here, and "balanced" means sum(signed) == 0.
 * (The +/- choice is a convention for the balance check only; which direction *increases* a given
 * account depends on its {@link AccountType}.)
 */
public enum PostingDirection {
    DEBIT,
    CREDIT;

    /** Signed minor units for the balance invariant: debits positive, credits negative. */
    public long signed(long amountMinor) {
        return this == DEBIT ? amountMinor : -amountMinor;
    }
}
