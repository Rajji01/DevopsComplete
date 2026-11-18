package com.shopflow.ledger.domain;

/**
 * The accounting-equation class of an account: Assets = Liabilities + Equity (+ Revenue - Expense).
 * A customer's wallet balance the platform owes them is a LIABILITY; the platform's bank cash is an
 * ASSET; a processing fee earned is REVENUE. The type fixes which side (debit/credit) increases the
 * account — the ledger stays balanced regardless, but the type is what makes a balance interpretable.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE
}
