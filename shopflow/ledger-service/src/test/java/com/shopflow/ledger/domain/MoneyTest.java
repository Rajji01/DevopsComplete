package com.shopflow.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

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
