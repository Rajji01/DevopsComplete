package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class FxRateTest {

    private static final Instant T = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void appliesTheRateAndRoundsOnceInTheQuoteCurrency() {
        FxRate usdInr = new FxRate("USD", "INR", new BigDecimal("83.50"), T);
        // 100.00 USD -> 8350.00 INR = 835000 paise
        assertThat(usdInr.apply(Money.of(10000, "USD"))).isEqualTo(Money.of(835000, "INR"));
    }

    @Test
    void roundingIsHalfEvenAtTheQuoteMinorUnit() {
        FxRate usdInr = new FxRate("USD", "INR", new BigDecimal("83.335"), T);
        // 1.00 USD * 83.335 = 83.335 -> 8333.5 paise -> HALF_EVEN -> 8334 (4 is even)
        assertThat(usdInr.apply(Money.of(100, "USD")).minorUnits()).isEqualTo(8334L);
    }

    @Test
    void targetCurrencyExponentIsRespected() {
        FxRate usdJpy = new FxRate("USD", "JPY", new BigDecimal("150"), T);
        // 100.00 USD -> 15000 JPY (0 minor-unit digits: 15000 minor units, not 1500000)
        assertThat(usdJpy.apply(Money.of(10000, "USD"))).isEqualTo(Money.of(15000, "JPY"));
    }

    @Test
    void convertingTheWrongCurrencyIsAProgrammingError() {
        FxRate usdInr = new FxRate("USD", "INR", new BigDecimal("83.50"), T);
        assertThatThrownBy(() -> usdInr.apply(Money.of(100, "INR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDegenerateRates() {
        assertThatThrownBy(() -> new FxRate("USD", "USD", new BigDecimal("1"), T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FxRate("USD", "INR", new BigDecimal("0"), T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FxRate("USD", "INR", new BigDecimal("83.5"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
