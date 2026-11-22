package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class FxPnlTest {

    private static final Instant T = Instant.parse("2026-11-22T00:00:00Z");

    // 100.00 USD was booked for 8350.00 INR (the trade rate was 83.50)
    private static final Money BASE_HELD = Money.of(10000, "USD");
    private static final Money QUOTE_BOOKED = Money.of(835000, "INR");

    @Test
    void aStrongerBaseAtSquareOffIsAGain() {
        FxRate squareOff = new FxRate("USD", "INR", new BigDecimal("84.00"), T);
        // 100.00 USD now worth 8400.00 INR; booked 8350.00 -> +50.00 INR gain
        assertThat(FxPnl.realised(BASE_HELD, QUOTE_BOOKED, squareOff)).isEqualTo(Money.of(5000, "INR"));
    }

    @Test
    void aWeakerBaseAtSquareOffIsALoss() {
        FxRate squareOff = new FxRate("USD", "INR", new BigDecimal("83.00"), T);
        // 100.00 USD now worth 8300.00 INR; booked 8350.00 -> -50.00 INR loss
        assertThat(FxPnl.realised(BASE_HELD, QUOTE_BOOKED, squareOff)).isEqualTo(Money.of(-5000, "INR"));
    }

    @Test
    void squaringOffAtTheBookedRateIsZeroPnl() {
        FxRate squareOff = new FxRate("USD", "INR", new BigDecimal("83.50"), T);
        assertThat(FxPnl.realised(BASE_HELD, QUOTE_BOOKED, squareOff).isZero()).isTrue();
    }

    @Test
    void aSquareOffRateIntoTheWrongCurrencyIsAProgrammingError() {
        // position booked in INR, but this rate settles into JPY
        FxRate wrong = new FxRate("USD", "JPY", new BigDecimal("150"), T);
        assertThatThrownBy(() -> FxPnl.realised(BASE_HELD, QUOTE_BOOKED, wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
