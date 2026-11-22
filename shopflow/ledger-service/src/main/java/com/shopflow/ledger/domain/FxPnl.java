package com.shopflow.ledger.domain;

/**
 * Realised FX gain/loss — what the FX_CLEARING position is worth when it's finally squared off, versus
 * what it was booked at. After a conversion (see {@link FxConversion}) the clearing accounts hold a
 * position: +base in one, -quote in the other, locked in at the rate captured on the trade. The base
 * currency keeps moving against the quote, so by the time the position is actually settled the base you
 * hold is worth a different amount of quote than you booked — that difference, crystallised at square-off,
 * is the realised gain or loss, and it belongs in a dedicated {@code FX_GAIN_LOSS} account (it is real
 * P&L, not a rounding artifact).
 *
 * This is the pure computation (the number); the booking of the square-off entry is a separate step, the
 * same way {@link FxConversion} is the booking model and {@link LedgerService} does the persistence.
 */
public final class FxPnl {

    private FxPnl() {
    }

    /**
     * The realised P&L, in the quote currency, of squaring off {@code baseHeld} (a base-currency position,
     * e.g. +100.00 USD held in {@code FX_CLEARING:USD}) that was originally booked for {@code quoteBooked}
     * (the quote credited at the trade rate, e.g. 8350.00 INR), now settled at {@code squareOffRate}.
     *
     * P&L = (baseHeld valued at squareOffRate) − quoteBooked. POSITIVE = gain (the base strengthened vs
     * the booked rate), NEGATIVE = loss. {@code squareOffRate} must convert the held base currency into the
     * booked quote currency (a mismatch is a programming error, like the rest of the Money/FxRate contract).
     */
    public static Money realised(Money baseHeld, Money quoteBooked, FxRate squareOffRate) {
        Money squareOffValue = squareOffRate.apply(baseHeld); // validates baseHeld.currency == rate.base, rounds in quote
        if (!squareOffValue.currency().equals(quoteBooked.currency())) {
            throw new IllegalArgumentException(
                    "square-off is in " + squareOffValue.currency() + " but the position was booked in "
                            + quoteBooked.currency());
        }
        return squareOffValue.plus(quoteBooked.negate()); // same currency -> safe subtract; sign is the P&L
    }
}
