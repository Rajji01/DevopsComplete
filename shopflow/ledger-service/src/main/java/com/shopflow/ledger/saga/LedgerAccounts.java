package com.shopflow.ledger.saga;

/**
 * The platform's control accounts for the payment flow, and the currency the flow uses. The
 * accounts themselves are seeded as reference data by Flyway ({@code V2__control_accounts.sql}),
 * NOT by an ApplicationRunner — a runner runs after context refresh, but the @KafkaListener that
 * posts to these accounts starts consuming during refresh, so a runner-based seed could lose the
 * startup race and let the first event hit a missing account (Walk 106). A migration runs before
 * any listener consumes, so the accounts always exist first.
 *
 * The capture double-entry is: DEBIT {@code CASH} (an ASSET — cash received goes up on the debit
 * side) and CREDIT {@code SALES_REVENUE} (a REVENUE account — goes up on the credit side); a refund
 * is the exact reverse. Every entry balances, so CASH and SALES_REVENUE always move together.
 */
public final class LedgerAccounts {

    private LedgerAccounts() {
    }

    /** Platform cash/settlement account (ASSET): increased by a debit when a customer pays. */
    public static final String CASH = "CASH";

    /** Sales revenue recognised on capture (REVENUE): increased by a credit. */
    public static final String SALES_REVENUE = "SALES_REVENUE";

    /** All amounts in this flow are rupees; multi-currency + FX is increment 4. */
    public static final String CURRENCY = "INR";
}
