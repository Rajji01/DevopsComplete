package com.shopflow.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The same three production complaints, run against BOTH consumers so the bug and its fix are provable
 * side by side (the "api" under test is each consumer's onMessage, called directly — no broker needed).
 *
 *   S1 restart        broken: settles twice   |  fixed: settles once (DB remembers)
 *   S2 two pods       broken: settles twice   |  fixed: settles once (DB is shared)
 *   S3 credit fails   broken: debit stranded  |  fixed: whole thing rolls back (atomic)
 *
 * Each test uses its OWN account + trade-id names so the tests are independent of each other and of the
 * shared @SpringBootTest database (no cross-test cleanup to get wrong).
 */
@SpringBootTest
@ActiveProfiles("test")
class SettlementScenarioTest {

    @Autowired
    private SettlementLedger ledger;
    @Autowired
    private SettlementConsumer fixed; // the Spring-proxied bean, so its @Transactional actually applies
    @Autowired
    private SettlementBalanceRepository balances;
    @Autowired
    private ProcessedSettlementRepository processed;

    private void seed(String account, long balance) {
        balances.save(new SettlementBalance(account, balance));
    }

    private long balanceOf(String account) {
        return balances.findById(account).orElseThrow().getBalanceMinor();
    }

    private SettlementEvent trade(String tradeId, String from, String to) {
        return new SettlementEvent(tradeId, from, to, 100, "INR");
    }

    // ---------------------------------- BROKEN ----------------------------------

    @Test
    void broken_S1_aRestartReprocessesTheSameTrade() {
        seed("acc-b1", 1000);
        seed("cp-b1", 0);

        new BrokenSettlementConsumer(ledger).onMessage(trade("tr-b1", "acc-b1", "cp-b1"));
        // "restart": a fresh consumer = a fresh, empty HashSet; the same trade is redelivered
        new BrokenSettlementConsumer(ledger).onMessage(trade("tr-b1", "acc-b1", "cp-b1"));

        assertThat(balanceOf("acc-b1")).isEqualTo(1000 - 200); // settled TWICE — the dedup memory was lost
    }

    @Test
    void broken_S2_twoPodsEachDedupeInTheirOwnMemory() {
        seed("acc-b2", 1000);
        seed("cp-b2", 0);

        new BrokenSettlementConsumer(ledger).onMessage(trade("tr-b2", "acc-b2", "cp-b2")); // pod A
        new BrokenSettlementConsumer(ledger).onMessage(trade("tr-b2", "acc-b2", "cp-b2")); // pod B, own set

        assertThat(balanceOf("acc-b2")).isEqualTo(1000 - 200); // settled twice across pods
    }

    @Test
    void broken_S3_creditFailsButTheDebitAlreadyCommitted() {
        seed("acc-b3", 1000);
        // counterparty "missing-b3" is intentionally NOT seeded, so the credit fails

        assertThatThrownBy(() ->
                new BrokenSettlementConsumer(ledger).onMessage(trade("tr-b3", "acc-b3", "missing-b3")))
                .isInstanceOf(UnknownAccountException.class);

        assertThat(balanceOf("acc-b3")).isEqualTo(1000 - 100);     // money left the payer...
        assertThat(balances.findById("missing-b3")).isEmpty();     // ...and reached no one: a half-settlement
    }

    // ---------------------------------- FIXED ----------------------------------

    @Test
    void fixed_S1_aRedeliveryAfterRestartIsIgnored() {
        seed("acc-f1", 1000);
        seed("cp-f1", 0);

        fixed.onMessage(trade("tr-f1", "acc-f1", "cp-f1"));
        fixed.onMessage(trade("tr-f1", "acc-f1", "cp-f1")); // restart / redelivery — DB still has the row

        assertThat(balanceOf("acc-f1")).isEqualTo(1000 - 100); // settled exactly once
    }

    @Test
    void fixed_S2_everyPodSharesTheSameDedupTruth() {
        seed("acc-f2", 1000);
        seed("cp-f2", 0);

        fixed.onMessage(trade("tr-f2", "acc-f2", "cp-f2"));
        fixed.onMessage(trade("tr-f2", "acc-f2", "cp-f2")); // any other pod reads the same processed row

        assertThat(balanceOf("acc-f2")).isEqualTo(1000 - 100);
    }

    @Test
    void fixed_S3_aFailedCreditRollsBackTheDebitAtomically() {
        seed("acc-f3", 1000);
        // counterparty "missing-f3" not seeded -> credit fails

        assertThatThrownBy(() -> fixed.onMessage(trade("tr-f3", "acc-f3", "missing-f3")))
                .isInstanceOf(UnknownAccountException.class);

        assertThat(balanceOf("acc-f3")).isEqualTo(1000);          // debit rolled back with the failed credit
        assertThat(processed.existsById("tr-f3")).isFalse();      // not marked done -> a redelivery can retry
    }
}
