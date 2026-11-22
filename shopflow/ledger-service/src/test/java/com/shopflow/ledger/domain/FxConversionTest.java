package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class FxConversionTest {

    private static final FxRate USD_INR =
            new FxRate("USD", "INR", new BigDecimal("83.50"), Instant.parse("2026-11-22T00:00:00Z"));

    private static FxConversion.Result convert() {
        // the two clearing accounts are per-currency, matching the ledger's one-currency-per-account rule
        return FxConversion.convert("CUSTOMER_USD", "CASH_INR", "FX_CLEARING:USD", "FX_CLEARING:INR",
                Money.of(10000, "USD"), USD_INR);
    }

    @Test
    void convertsAtTheCapturedRate() {
        FxConversion.Result r = convert();
        assertThat(r.sold()).isEqualTo(Money.of(10000, "USD"));       // 100.00 USD
        assertThat(r.bought()).isEqualTo(Money.of(835000, "INR"));    // -> 8350.00 INR
    }

    @Test
    void eachLegIsSingleCurrencyAndBalances() {
        FxConversion.Result r = convert();
        // the whole point: a cross-currency move is two entries, each of which passes DoubleEntry on its own
        assertThatCode(() -> DoubleEntry.validate("USD", r.sellLeg())).doesNotThrowAnyException();
        assertThatCode(() -> DoubleEntry.validate("INR", r.buyLeg())).doesNotThrowAnyException();
        assertThat(r.sellLeg()).allSatisfy(l -> assertThat(l.amount().currency()).isEqualTo("USD"));
        assertThat(r.buyLeg()).allSatisfy(l -> assertThat(l.amount().currency()).isEqualTo("INR"));
    }

    @Test
    void eachClearingAccountIsSingleCurrencyAndCarriesItsSideOfThePosition() {
        FxConversion.Result r = convert();
        // base clearing is debited the base amount (and is a USD account) ...
        assertThat(r.sellLeg()).anySatisfy(l -> {
            assertThat(l.accountCode()).isEqualTo("FX_CLEARING:USD");
            assertThat(l.direction()).isEqualTo(PostingDirection.DEBIT);
            assertThat(l.amount()).isEqualTo(Money.of(10000, "USD"));
        });
        // ... quote clearing is credited the quote amount (a distinct INR account) — one shared account
        // could not hold both, which is exactly why convert() takes two.
        assertThat(r.buyLeg()).anySatisfy(l -> {
            assertThat(l.accountCode()).isEqualTo("FX_CLEARING:INR");
            assertThat(l.direction()).isEqualTo(PostingDirection.CREDIT);
            assertThat(l.amount()).isEqualTo(Money.of(835000, "INR"));
        });
    }
}
