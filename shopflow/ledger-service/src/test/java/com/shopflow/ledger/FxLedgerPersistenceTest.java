package com.shopflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.domain.AccountType;
import com.shopflow.ledger.domain.FxRate;
import com.shopflow.ledger.domain.Money;

@SpringBootTest
@ActiveProfiles("test")
@Transactional   // each test rolls back, so derived balances stay isolated
class FxLedgerPersistenceTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountService accounts;

    private static final FxRate USD_INR =
            new FxRate("USD", "INR", new BigDecimal("83.50"), Instant.parse("2026-11-22T00:00:00Z"));

    @BeforeEach
    void businessAccounts() {
        // the from/to business accounts must exist (deliberate); the FX_CLEARING:* control accounts
        // are ensured by convertCurrency itself
        accounts.create("CUSTOMER_USD", AccountType.ASSET, "USD");
        accounts.create("CASH_INR", AccountType.ASSET, "INR");
    }

    @Test
    void convertsAtomicallyMovingBothCurrenciesThroughPerCurrencyClearing() {
        LedgerService.FxPosting p = ledger.convertCurrency(UUID.randomUUID().toString(), "fx-" + UUID.randomUUID(),
                "CUSTOMER_USD", "CASH_INR", Money.of(10000, "USD"), USD_INR);

        assertThat(p.sold()).isEqualTo(Money.of(10000, "USD"));        // 100.00 USD
        assertThat(p.bought()).isEqualTo(Money.of(835000, "INR"));     // -> 8350.00 INR

        // base side: USD left the customer (CR) into the base clearing (DR)
        assertThat(ledger.balanceOf("CUSTOMER_USD")).isEqualTo(Money.of(-10000, "USD"));
        assertThat(ledger.balanceOf("FX_CLEARING:USD")).isEqualTo(Money.of(10000, "USD"));
        // quote side: INR arrived at cash (DR) out of the quote clearing (CR)
        assertThat(ledger.balanceOf("CASH_INR")).isEqualTo(Money.of(835000, "INR"));
        assertThat(ledger.balanceOf("FX_CLEARING:INR")).isEqualTo(Money.of(-835000, "INR"));
    }

    @Test
    void replayingTheSameKeyDoesNotDoublePost() {
        String key = UUID.randomUUID().toString();
        String ref = "fx-" + UUID.randomUUID();
        ledger.convertCurrency(key, ref, "CUSTOMER_USD", "CASH_INR", Money.of(10000, "USD"), USD_INR);
        ledger.convertCurrency(key, ref, "CUSTOMER_USD", "CASH_INR", Money.of(10000, "USD"), USD_INR);

        // balances reflect exactly one conversion, not two
        assertThat(ledger.balanceOf("CASH_INR")).isEqualTo(Money.of(835000, "INR"));
        assertThat(ledger.balanceOf("CUSTOMER_USD")).isEqualTo(Money.of(-10000, "USD"));
    }

    @Test
    void replayingTheSameKeyWithADifferentBodyReturnsWhatWasActuallyBooked() {
        String key = UUID.randomUUID().toString();
        String ref = "fx-" + UUID.randomUUID();
        LedgerService.FxPosting first = ledger.convertCurrency(key, ref,
                "CUSTOMER_USD", "CASH_INR", Money.of(10000, "USD"), USD_INR);

        // same idempotency key, but a DIFFERENT amount AND rate (a buggy client, or a mutated retry).
        // The ledger must treat this as the already-done conversion and hand back the ORIGINAL booking —
        // not echo the new body's numbers for a conversion that was never posted.
        FxRate differentRate =
                new FxRate("USD", "INR", new BigDecimal("90.00"), Instant.parse("2026-11-22T00:00:00Z"));
        LedgerService.FxPosting replay = ledger.convertCurrency(key, ref,
                "CUSTOMER_USD", "CASH_INR", Money.of(55555, "USD"), differentRate);

        // the response echoes the FIRST booking (10000 USD -> 835000 INR), not 55555 USD @ 90.00
        assertThat(replay.sold()).isEqualTo(first.sold()).isEqualTo(Money.of(10000, "USD"));
        assertThat(replay.bought()).isEqualTo(first.bought()).isEqualTo(Money.of(835000, "INR"));
        assertThat(replay.sell().getId()).isEqualTo(first.sell().getId());
        assertThat(replay.buy().getId()).isEqualTo(first.buy().getId());

        // and the books still hold exactly one conversion
        assertThat(ledger.balanceOf("CASH_INR")).isEqualTo(Money.of(835000, "INR"));
        assertThat(ledger.balanceOf("CUSTOMER_USD")).isEqualTo(Money.of(-10000, "USD"));
    }
}
