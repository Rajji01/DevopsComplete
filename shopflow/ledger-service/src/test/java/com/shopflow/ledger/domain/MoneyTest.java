package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void fromMajorConvertsRupeesToPaise() {
        assertThat(Money.fromMajor(new BigDecimal("749.99"), "INR"))
                .isEqualTo(Money.of(74999, "INR"));
        assertThat(Money.fromMajor(new BigDecimal("500"), "INR"))
                .isEqualTo(Money.of(50000, "INR"));
    }

    @Test
    void fromMajorRoundsHalfToEven() {
        // banker's rounding: 1.005 -> 100 (round to even), 1.015 -> 102 (round to even), not always up
        assertThat(Money.fromMajor(new BigDecimal("1.005"), "INR").minorUnits()).isEqualTo(100L);
        assertThat(Money.fromMajor(new BigDecimal("1.015"), "INR").minorUnits()).isEqualTo(102L);
    }

    @Test
    void plusAddsMinorUnitsOfTheSameCurrency() {
        assertThat(Money.of(1000, "INR").plus(Money.of(250, "INR")))
                .isEqualTo(Money.of(1250, "INR"));
    }

    @Test
    void negateMirrorsTheAmountSoTheTwoLegsSumToZero() {
        Money debit = Money.of(1000, "INR");
        assertThat(debit.plus(debit.negate()).isZero()).isTrue();
    }

    @Test
    void addingDifferentCurrenciesIsAProgrammingError() {
        assertThatThrownBy(() -> Money.of(1000, "INR").plus(Money.of(1000, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currencyMustBeIso4217ThreeLetters() {
        assertThatThrownBy(() -> Money.of(100, "RUPEE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
