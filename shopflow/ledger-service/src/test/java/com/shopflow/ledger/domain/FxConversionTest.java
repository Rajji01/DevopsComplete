package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class FxConversionTest {

    private static final FxRate USD_INR =
            new FxRate("USD", "INR", new BigDecimal("83.50"), Instant.parse("2026-11-22T00:00:00Z"));

    @Test
    void convertsAtTheCapturedRate() {
        FxConversion.Result r = FxConversion.convert("CUSTOMER_USD", "CASH_INR", "FX_CLEARING",
                Money.of(10000, "USD"), USD_INR);
        assertThat(r.sold()).isEqualTo(Money.of(10000, "USD"));       // 100.00 USD
        assertThat(r.bought()).isEqualTo(Money.of(835000, "INR"));    // -> 8350.00 INR
    }

    @Test
    void eachLegIsSingleCurrencyAndBalances() {
        FxConversion.Result r = FxConversion.convert("CUSTOMER_USD", "CASH_INR", "FX_CLEARING",
                Money.of(10000, "USD"), USD_INR);
        // the whole point: a cross-currency move is two entries, each of which passes DoubleEntry on its own
        assertThatCode(() -> DoubleEntry.validate("USD", r.sellLeg())).doesNotThrowAnyException();
        assertThatCode(() -> DoubleEntry.validate("INR", r.buyLeg())).doesNotThrowAnyException();
        assertThat(r.sellLeg()).allSatisfy(l -> assertThat(l.amount().currency()).isEqualTo("USD"));
        assertThat(r.buyLeg()).allSatisfy(l -> assertThat(l.amount().currency()).isEqualTo("INR"));
    }

    @Test
    void theFxClearingAccountIsOnBothLegsCarryingThePosition() {
        FxConversion.Result r = FxConversion.convert("CUSTOMER_USD", "CASH_INR", "FX_CLEARING",
                Money.of(10000, "USD"), USD_INR);
        // clearing is debited the base and credited the quote -> it holds the two-currency FX position
        assertThat(r.sellLeg()).anySatisfy(l -> {
            assertThat(l.accountCode()).isEqualTo("FX_CLEARING");
            assertThat(l.direction()).isEqualTo(PostingDirection.DEBIT);
            assertThat(l.amount()).isEqualTo(Money.of(10000, "USD"));
        });
        assertThat(r.buyLeg()).anySatisfy(l -> {
            assertThat(l.accountCode()).isEqualTo("FX_CLEARING");
            assertThat(l.direction()).isEqualTo(PostingDirection.CREDIT);
            assertThat(l.amount()).isEqualTo(Money.of(835000, "INR"));
        });
    }
}
