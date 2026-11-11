package com.shopflow.ledger.recon;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Default statement source until a real PSP/bank adapter is wired: no statement available. The
 * scheduled job treats an empty statement as "nothing to reconcile against" and skips, so a fresh
 * deployment does not flood the exception buckets with MISSING_IN_STATEMENT for every booked entry
 * just because no feed is configured yet. A real adapter replaces this bean.
 */
@Component
public class EmptySettlementStatementSource implements SettlementStatementSource {

    @Override
    public List<SettlementLine> fetchStatement() {
        return List.of();
    }
}
