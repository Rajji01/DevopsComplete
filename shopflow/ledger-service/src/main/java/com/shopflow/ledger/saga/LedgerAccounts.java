package com.shopflow.ledger.saga;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.shopflow.ledger.AccountService;
import com.shopflow.ledger.domain.AccountType;

/**
 * The platform's control accounts for the payment flow, created deliberately at startup — not
 * auto-conjured by a posting (AccountService's rule: a typo'd code must be a rejected posting, not
 * a silent new account that absorbs money). Seeding them up front is the "deliberate" creation.
 *
 * The capture double-entry is: DEBIT {@code CASH} (an ASSET — cash we received goes up on the debit
 * side) and CREDIT {@code SALES_REVENUE} (a REVENUE account — revenue goes up on the credit side).
 * A refund is the exact reverse. Because every entry balances, CASH and SALES_REVENUE always move
 * together and the sum of all balances stays zero.
 *
 * {@code AccountService.create} is idempotent (unique code + race-safe), so running this on every
 * boot is safe and also self-heals a fresh database.
 */
@Configuration
public class LedgerAccounts {

    /** Platform cash/settlement account (ASSET): increased by a debit when a customer pays. */
    public static final String CASH = "CASH";

    /** Sales revenue recognised on capture (REVENUE): increased by a credit. */
    public static final String SALES_REVENUE = "SALES_REVENUE";

    /** All amounts in this flow are rupees; multi-currency + FX is increment 4. */
    public static final String CURRENCY = "INR";

    @Bean
    ApplicationRunner seedLedgerAccounts(AccountService accounts) {
        return args -> {
            accounts.create(CASH, AccountType.ASSET, CURRENCY);
            accounts.create(SALES_REVENUE, AccountType.REVENUE, CURRENCY);
        };
    }
}
