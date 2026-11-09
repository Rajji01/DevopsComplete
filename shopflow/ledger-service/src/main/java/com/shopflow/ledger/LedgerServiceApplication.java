package com.shopflow.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Double-entry ledger service (payments / post-trade core).
 *
 * Every money movement in the system is recorded as a balanced journal entry: two or more
 * immutable postings (debit + credit) that sum to zero. Balances are DERIVED from postings,
 * never stored as a mutable truth — the append-only posting log IS the source of truth, which
 * is exactly what makes it auditable (reg reporting) and reconcilable (ledger vs bank statement).
 *
 * OSTTRA mapping: a journal entry per trade/payment event, its status walking the trade
 * lifecycle (INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED -> RECONCILED), postings being the
 * debit/credit legs that netting and position are computed from.
 *
 * Skeleton (Walk 96): domain, the double-entry invariant and the state machine are real and
 * tested; persistence, the REST API and the inbound saga consumer are stubbed TODOs by increment.
 */
@SpringBootApplication
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
