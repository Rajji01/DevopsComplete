package com.shopflow.ledger.domain;

/**
 * The trade/payment lifecycle a journal entry walks — the explicit state machine the roadmap
 * calls out, where auth, capture and settlement are DELIBERATELY three different states, not one
 * "paid". Illegal transitions are rejected ({@link #canTransitionTo}), so a bug or a replayed
 * out-of-order event can never, say, settle an entry that was never captured.
 *
 * OSTTRA lifecycle: capture -> confirmation (AUTHORIZED/CAPTURED) -> clearing/settlement
 * (SETTLED, T+1/T+2) -> reconciliation (RECONCILED). REVERSED is the compensating path
 * (refund/reversal); FAILED is a terminal dead end for an entry that never moved money.
 */
public enum JournalEntryStatus {
    INITIATED,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    RECONCILED,
    FAILED,
    REVERSED;

    /** Allowed forward (and compensating) transitions. RECONCILED / FAILED / REVERSED are terminal. */
    public boolean canTransitionTo(JournalEntryStatus next) {
        return switch (this) {
            case INITIATED -> next == AUTHORIZED || next == FAILED;
            case AUTHORIZED -> next == CAPTURED || next == FAILED || next == REVERSED;
            case CAPTURED -> next == SETTLED || next == REVERSED;
            case SETTLED -> next == RECONCILED || next == REVERSED;
            case RECONCILED, FAILED, REVERSED -> false;
        };
    }

    public boolean isTerminal() {
        return this == RECONCILED || this == FAILED || this == REVERSED;
    }
}
