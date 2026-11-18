package com.shopflow.ledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A per-account running balance (minor units). Debited/credited by the settlement consumers. */
@Entity
@Table(name = "settlement_balance")
public class SettlementBalance {

    @Id
    @Column(length = 64)
    private String account;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    protected SettlementBalance() {
    }

    public SettlementBalance(String account, long balanceMinor) {
        this.account = account;
        this.balanceMinor = balanceMinor;
    }

    public String getAccount() {
        return account;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }
}
