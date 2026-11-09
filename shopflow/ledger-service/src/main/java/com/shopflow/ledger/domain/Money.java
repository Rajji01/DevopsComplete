package com.shopflow.ledger.domain;

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
