package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shopflow.ledger.domain.PostingDirection;

class DoubleEntryTest {

    private static PostingLine debit(long minor, String ccy) {
        return new PostingLine("CASH", PostingDirection.DEBIT, Money.of(minor, ccy));
    }

    private static PostingLine credit(long minor, String ccy) {
        return new PostingLine("CUSTOMER_WALLET:c1", PostingDirection.CREDIT, Money.of(minor, ccy));
    }

    @Test
    void balancedEntryPasses() {
        // debit 1000 cash, credit 1000 wallet — money moved, not created
        DoubleEntry.validate("INR", List.of(debit(1000, "INR"), credit(1000, "INR")));
        // no exception = valid
    }

    @Test
    void unbalancedEntryIsRejected() {
        assertThatThrownBy(() -> DoubleEntry.validate("INR", List.of(debit(1000, "INR"), credit(999, "INR"))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("signed sum = 1");
    }

    @Test
    void mixedCurrencyIsRejected() {
        assertThatThrownBy(() -> DoubleEntry.validate("INR", List.of(debit(1000, "INR"), credit(1000, "USD"))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void singleLegIsRejected() {
        assertThatThrownBy(() -> DoubleEntry.validate("INR", List.of(debit(1000, "INR"))))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void amountsAreStoredPositiveSoNegativeIsRejectedAtTheLine() {
        // the sign lives in the direction, never in the amount
        Throwable t = catchThrowable(() -> new PostingLine("CASH", PostingDirection.DEBIT, Money.of(-5, "INR")));
        assertThat(t).isInstanceOf(IllegalArgumentException.class);
    }
}
