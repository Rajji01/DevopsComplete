package com.shopflow.ledger.domain;

/** A posting referenced an account code that does not exist in the chart of accounts. */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String code) {
        super("no ledger account with code: " + code);
    }
}
