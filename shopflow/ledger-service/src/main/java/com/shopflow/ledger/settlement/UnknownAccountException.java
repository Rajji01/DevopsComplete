package com.shopflow.ledger.settlement;

/** Thrown when a debit/credit targets an account that does not exist — a settlement that cannot complete. */
public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(String account) {
        super("unknown settlement account: " + account);
    }
}
