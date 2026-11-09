package com.shopflow.ledger.domain;

/** A journal entry whose debits and credits do not sum to zero (or mixes currencies) — rejected. */
public class UnbalancedEntryException extends RuntimeException {
    public UnbalancedEntryException(String message) {
        super(message);
    }
}
