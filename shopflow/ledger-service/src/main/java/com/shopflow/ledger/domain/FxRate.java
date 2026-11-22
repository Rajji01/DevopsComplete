package com.shopflow.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A captured FX rate: how many {@code quote} units per ONE {@code base} unit, as of {@code capturedAt}.
 *
 * In a trade that presents in one currency and settles in another, the rate used must be CAPTURED and
 * stored with the trade, not looked up again at settlement — the rate moves every second, so a rate
 * re-fetched later is a different number, and a settlement computed from it will not tie out against
 * what the customer was actually quoted. Capturing the rate (and when) is the whole point: it makes the
 * conversion reproducible and auditable, which is exactly what reconciliation and reg-reporting need.
 *
 * {@link #apply} does the conversion and makes the rounding decision ONCE, in the target currency's
 * minor unit (via {@link Money#fromMajor}, HALF_EVEN) — the same "round once, at the boundary" rule as
 * the rest of {@link Money}. Converting across currencies is otherwise a programming error (never a
 * silent coercion), mirroring {@link Money#plus}.
 */
public record FxRate(String base, String quote, BigDecimal rate, Instant capturedAt) {

    public FxRate {
        if (base == null || base.length() != 3) {
            throw new IllegalArgumentException("base must be an ISO-4217 code, got: " + base);
        }
        if (quote == null || quote.length() != 3) {
            throw new IllegalArgumentException("quote must be an ISO-4217 code, got: " + quote);
        }
        if (base.equals(quote)) {
            throw new IllegalArgumentException("base and quote must differ, both were " + base);
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive, got: " + rate);
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt required (a rate without a timestamp is not auditable)");
        }
    }

    /**
     * Convert a {@code base}-currency amount into {@code quote} currency at this rate. The amount must be
     * in the base currency — a mismatch is a programming error, not a silent re-interpretation. Rounding
     * happens exactly once, in the quote currency's minor unit.
     */
    public Money apply(Money amount) {
        if (!amount.currency().equals(base)) {
            throw new IllegalArgumentException(
                    "amount is in " + amount.currency() + " but this rate converts from " + base);
        }
        BigDecimal quoteMajor = amount.toMajor().multiply(rate);
        return Money.fromMajor(quoteMajor, quote);
    }

    /** The inverse quote (base per one quote unit) — for display and round-trip reasoning, not for re-deriving. */
    public BigDecimal inverseRate() {
        return BigDecimal.ONE.divide(rate, 10, RoundingMode.HALF_EVEN);
    }
}
