package com.shopflow.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Money as (minor units, currency) — never a float/double (the classic payments filter question).
 * `minorUnits` is the smallest indivisible unit of the currency: paise for INR, cents for USD.
 * Storing an integer count of minor units means no binary-floating rounding drift can ever
 * accumulate across millions of postings — the rounding decision is made explicitly, once, at the
 * boundary where a fractional amount enters (banker's rounding; see {@code fromMajor}, TODO
 * increment 1 when real pricing flows in), not silently on every arithmetic op.
 *
 * Immutable value type: every operation returns a new Money, and cross-currency arithmetic is a
 * programming error, not a silent coercion.
 */
public record Money(long minorUnits, String currency) {

    public Money {
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("ISO-4217 3-letter currency required, got: " + currency);
        }
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Convert a major-unit decimal amount (e.g. 749.99 rupees, as it arrives on a PaymentCaptured
     * event) into minor units, making the rounding decision explicitly, ONCE, right here at the
     * boundary — the whole point of the (minorUnits, currency) design (see the type javadoc).
     *
     * Rounding is HALF_EVEN (banker's rounding): it rounds a half to the nearest even digit, so a
     * stream of .005 amounts does not bias systematically upward the way HALF_UP would — the
     * convention mandated for financial sums. The scale is the currency's ISO-4217 minor-unit
     * exponent (see {@link #minorUnitDigits}), NOT a hardcoded 2 — so 500 JPY becomes 500 minor
     * units (yen has no sub-unit), not 50000, and 1.234 BHD becomes 1234 fils (FX / inc-4c).
     */
    public static Money fromMajor(BigDecimal major, String currency) {
        if (major == null) {
            throw new IllegalArgumentException("amount required");
        }
        long minor = major.movePointRight(minorUnitDigits(currency))
                .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        return new Money(minor, currency);
    }

    /** The major-unit decimal view (e.g. 74999 paise -> 749.99 INR, 500 minor -> 500 JPY), scaled per currency. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(minorUnitDigits(currency));
    }

    /**
     * ISO-4217 minor-unit exponent: how many decimal places the currency's minor unit has. Most are 2
     * (paise, cents), but a correct multi-currency ledger must not assume it: JPY/KRW have 0 (the yen
     * has no sub-unit), and a few Gulf currencies have 3 (BHD/KWD fils). Hardcoding 2 would inflate a
     * JPY amount 100x and a BHD amount 10x at the fromMajor boundary. Only the common exceptions are
     * listed; every other currency defaults to 2.
     */
    public static int minorUnitDigits(String currency) {
        if (ZERO_DECIMAL.contains(currency)) {
            return 0;
        }
        if (THREE_DECIMAL.contains(currency)) {
            return 3;
        }
        return 2;
    }

    private static final Set<String> ZERO_DECIMAL = Set.of("JPY", "KRW", "VND", "CLP", "ISK", "XOF", "XAF", "PYG", "UGX", "RWF");
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "KWD", "OMR", "JOD", "TND", "LYD", "IQD");

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /** The opposite leg: a debit's mirror credit, same magnitude, so the two sum to zero. */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            // FX (mixed-currency entries with an explicit conversion leg) is increment 4, not a silent add
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
