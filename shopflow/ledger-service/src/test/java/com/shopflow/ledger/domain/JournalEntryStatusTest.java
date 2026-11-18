package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JournalEntryStatusTest {

    @Test
    void theHappyTradeLifecycleIsAllowed() {
        assertThat(JournalEntryStatus.INITIATED.canTransitionTo(JournalEntryStatus.AUTHORIZED)).isTrue();
        assertThat(JournalEntryStatus.AUTHORIZED.canTransitionTo(JournalEntryStatus.CAPTURED)).isTrue();
        assertThat(JournalEntryStatus.CAPTURED.canTransitionTo(JournalEntryStatus.SETTLED)).isTrue();
        assertThat(JournalEntryStatus.SETTLED.canTransitionTo(JournalEntryStatus.RECONCILED)).isTrue();
    }

    @Test
    void settlingSomethingNeverCapturedIsIllegal() {
        // the whole point of auth != capture != settlement: you cannot skip states
        assertThat(JournalEntryStatus.AUTHORIZED.canTransitionTo(JournalEntryStatus.SETTLED)).isFalse();
        assertThat(JournalEntryStatus.INITIATED.canTransitionTo(JournalEntryStatus.CAPTURED)).isFalse();
    }

    @Test
    void reversalIsAllowedFromAnyMovedState() {
        assertThat(JournalEntryStatus.AUTHORIZED.canTransitionTo(JournalEntryStatus.REVERSED)).isTrue();
        assertThat(JournalEntryStatus.CAPTURED.canTransitionTo(JournalEntryStatus.REVERSED)).isTrue();
        assertThat(JournalEntryStatus.SETTLED.canTransitionTo(JournalEntryStatus.REVERSED)).isTrue();
    }

    @Test
    void terminalStatesGoNowhere() {
        assertThat(JournalEntryStatus.RECONCILED.isTerminal()).isTrue();
        assertThat(JournalEntryStatus.FAILED.isTerminal()).isTrue();
        assertThat(JournalEntryStatus.REVERSED.isTerminal()).isTrue();
        assertThat(JournalEntryStatus.RECONCILED.canTransitionTo(JournalEntryStatus.REVERSED)).isFalse();
    }
}
