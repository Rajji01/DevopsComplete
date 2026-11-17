package com.shopflow.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
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
 * Account "ACC" starts at 1000, a trade moves 100 to "CP". "MISSING" is intentionally never created so a
 * credit to it fails — that is how we force the S3 half-settlement deterministically.
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

    @BeforeEach
    void reset() {
        processed.deleteAll();
        balances.deleteAll();
        balances.save(new SettlementBalance("ACC", 1000));
        balances.save(new SettlementBalance("CP", 0));
    }

    private SettlementEvent trade(String id) {
        return new SettlementEvent(id, "ACC", "CP", 100, "INR");
    }

    private long balanceOf(String account) {
        return balances.findById(account).orElseThrow().getBalanceMinor();
    }

    // ---------------------------------- BROKEN ----------------------------------

    @Test
    void broken_S1_aRestartReprocessesTheSameTrade() {
        var beforeRestart = new BrokenSettlementConsumer(ledger);
        beforeRestart.onMessage(trade("T1"));

        var afterRestart = new BrokenSettlementConsumer(ledger); // restart = a fresh, empty HashSet
        afterRestart.onMessage(trade("T1"));

        assertThat(balanceOf("ACC")).isEqualTo(1000 - 200); // settled TWICE — the dedup memory was lost
    }

    @Test
    void broken_S2_twoPodsEachDedupeInTheirOwnMemory() {
        var podA = new BrokenSettlementConsumer(ledger);
        var podB = new BrokenSettlementConsumer(ledger);

        podA.onMessage(trade("T1"));
        podB.onMessage(trade("T1")); // podB's set never saw podA's

        assertThat(balanceOf("ACC")).isEqualTo(1000 - 200); // settled twice across pods
    }

    @Test
    void broken_S3_creditFailsButTheDebitAlreadyCommitted() {
        var pod = new BrokenSettlementConsumer(ledger);
        var toMissingCounterparty = new SettlementEvent("T1", "ACC", "MISSING", 100, "INR");

        assertThatThrownBy(() -> pod.onMessage(toMissingCounterparty))
                .isInstanceOf(UnknownAccountException.class);

        assertThat(balanceOf("ACC")).isEqualTo(1000 - 100);   // money left the payer...
        assertThat(balances.findById("MISSING")).isEmpty();   // ...and reached no one: a half-settlement
    }

    // ---------------------------------- FIXED ----------------------------------

    @Test
    void fixed_S1_aRedeliveryAfterRestartIsIgnored() {
        fixed.onMessage(trade("T1"));
        fixed.onMessage(trade("T1")); // restart / redelivery — the dedup row is still in the DB

        assertThat(balanceOf("ACC")).isEqualTo(1000 - 100); // settled exactly once
    }

    @Test
    void fixed_S2_everyPodSharesTheSameDedupTruth() {
        fixed.onMessage(trade("T1"));
        fixed.onMessage(trade("T1")); // any other pod reads the same processed_settlement row

        assertThat(balanceOf("ACC")).isEqualTo(1000 - 100);
    }

    @Test
    void fixed_S3_aFailedCreditRollsBackTheDebitAtomically() {
        var toMissingCounterparty = new SettlementEvent("T1", "ACC", "MISSING", 100, "INR");

        assertThatThrownBy(() -> fixed.onMessage(toMissingCounterparty))
                .isInstanceOf(UnknownAccountException.class);

        assertThat(balanceOf("ACC")).isEqualTo(1000);            // debit rolled back with the failed credit
        assertThat(processed.existsById("T1")).isFalse();        // not marked done -> a redelivery can retry
    }
}
