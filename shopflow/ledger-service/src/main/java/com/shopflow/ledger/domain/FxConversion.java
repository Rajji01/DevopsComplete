package com.shopflow.ledger.domain;

import java.util.List;

/**
 * How a cross-currency movement is booked in a single-currency double-entry ledger — the thing that
 * can't be a mixed-currency journal entry (DoubleEntry rejects legs whose currency differs from the
 * entry currency, because "debits == credits" is only meaningful within one currency).
 *
 * The answer is TWO balanced single-currency entries joined through an FX clearing account:
 *   - the SELL leg (base currency):  CR fromAccount, DR fxClearing   — the base amount leaves the
 *                                     source and is parked in clearing;
 *   - the BUY  leg (quote currency): DR toAccount,   CR fxClearing   — the converted amount arrives
 *                                     at the target out of clearing.
 * Each leg is single-currency and sums to zero, so each passes {@link DoubleEntry#validate} on its
 * own. The clearing account is left holding +base and -quote: that two-currency residual IS the book's
 * FX position, and the realised gain/loss is recognised later when the position is squared off at a
 * different rate (not in this single-conversion primitive — it only captures WHAT the trade converted).
 *
 * Amounts are computed with the captured {@link FxRate}, so the conversion is reproducible: the same
 * trade re-booked from the stored rate yields the same minor units, which is what lets it reconcile.
 */
public final class FxConversion {

    private FxConversion() {
    }

    /**
     * The two balanced legs of one conversion, plus the amounts (the base {@code sold} and the quote
     * {@code bought}) for the caller to persist/report.
     */
    public record Result(Money sold, Money bought, List<PostingLine> sellLeg, List<PostingLine> buyLeg) {
    }

    /**
     * Book moving {@code amount} (in the rate's base currency) out of {@code fromAccount} and the
     * converted quote amount into {@code toAccount}, through {@code fxClearingAccount}. {@code amount}
     * must be in {@code rate.base()} — enforced by {@link FxRate#apply} — so a wrong-currency amount is
     * a programming error, not a silent re-interpretation.
     */
    public static Result convert(String fromAccount, String toAccount, String fxClearingAccount,
                                 Money amount, FxRate rate) {
        Money bought = rate.apply(amount); // validates amount.currency() == rate.base(), rounds in quote

        List<PostingLine> sellLeg = List.of(
                new PostingLine(fromAccount, PostingDirection.CREDIT, amount),        // base leaves the source
                new PostingLine(fxClearingAccount, PostingDirection.DEBIT, amount));  // ...into FX clearing (base)

        List<PostingLine> buyLeg = List.of(
                new PostingLine(toAccount, PostingDirection.DEBIT, bought),           // quote arrives at the target
                new PostingLine(fxClearingAccount, PostingDirection.CREDIT, bought)); // ...out of FX clearing (quote)

        return new Result(amount, bought, sellLeg, buyLeg);
    }
}
