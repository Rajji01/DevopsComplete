package com.shopflow.ledger.domain;

import java.util.List;

/**
 * How a cross-currency movement is booked in a single-currency double-entry ledger — the thing that
 * can't be a mixed-currency journal entry (DoubleEntry rejects legs whose currency differs from the
 * entry currency, because "debits == credits" is only meaningful within one currency).
 *
 * The answer is TWO balanced single-currency entries joined through a PER-CURRENCY FX clearing account:
 *   - the SELL leg (base currency):  CR fromAccount, DR fxClearingBase   — the base amount leaves the
 *                                     source and is parked in the base-currency clearing account;
 *   - the BUY  leg (quote currency): DR toAccount,   CR fxClearingQuote  — the converted amount arrives
 *                                     at the target out of the quote-currency clearing account.
 * Each leg is single-currency and sums to zero, so each passes {@link DoubleEntry#validate} on its own.
 * The two clearing accounts are DISTINCT and single-currency on purpose: the ledger persists an account
 * with exactly one currency and {@code LedgerService} rejects a posting whose currency differs from the
 * account's, so one shared clearing account could not hold both sides — the base clearing is left +base
 * and the quote clearing -quote, and that cross-account pair IS the book's FX position, squared off
 * later at a rate that realises the gain/loss (not in this single-conversion primitive).
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
     * converted quote amount into {@code toAccount}, through the base- and quote-currency FX clearing
     * accounts. {@code amount} must be in {@code rate.base()} — enforced by {@link FxRate#apply} — so a
     * wrong-currency amount is a programming error, not a silent re-interpretation. The two clearing
     * accounts must be single-currency (base and quote respectively), matching how the ledger persists
     * and validates account currency; passing one account for both would fail to post.
     */
    public static Result convert(String fromAccount, String toAccount,
                                 String fxClearingBase, String fxClearingQuote,
                                 Money amount, FxRate rate) {
        Money bought = rate.apply(amount); // validates amount.currency() == rate.base(), rounds in quote

        List<PostingLine> sellLeg = List.of(
                new PostingLine(fromAccount, PostingDirection.CREDIT, amount),      // base leaves the source
                new PostingLine(fxClearingBase, PostingDirection.DEBIT, amount));   // ...into base FX clearing

        List<PostingLine> buyLeg = List.of(
                new PostingLine(toAccount, PostingDirection.DEBIT, bought),         // quote arrives at the target
                new PostingLine(fxClearingQuote, PostingDirection.CREDIT, bought)); // ...out of quote FX clearing

        return new Result(amount, bought, sellLeg, buyLeg);
    }
}
