package com.shopflow.ledger.settlement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money movements. Each of {@code debit}/{@code credit} is {@code @Transactional} with the default
 * propagation (REQUIRED), and that single fact is the hinge of the whole demo:
 *
 *  • called on their own (the BROKEN consumer makes no surrounding transaction) each one runs in and
 *    commits its OWN transaction — so a debit that commits followed by a credit that throws leaves the
 *    books half-settled (scenario S3).
 *  • called from inside the FIXED consumer's {@code @Transactional onMessage} they JOIN that outer
 *    transaction — debit + credit commit together or not at all (atomic), so S3 cannot happen.
 *
 * A zero-row update means the account does not exist; we throw rather than silently drop the movement.
 */
@Service
public class SettlementLedger {

    private final SettlementBalanceRepository balances;

    public SettlementLedger(SettlementBalanceRepository balances) {
        this.balances = balances;
    }

    @Transactional
    public void debit(String account, long amountMinor) {
        if (balances.addToBalance(account, -amountMinor) == 0) {
            throw new UnknownAccountException(account);
        }
    }

    @Transactional
    public void credit(String account, long amountMinor) {
        if (balances.addToBalance(account, amountMinor) == 0) {
            throw new UnknownAccountException(account);
        }
    }
}
