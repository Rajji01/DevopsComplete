package com.shopflow.payment;

import java.math.BigDecimal;

/**
 * The card processor (Razorpay, Stripe, ...). Behind an interface so the real one is a one-class change.
 *
 * A card charge is really TWO calls (Walk 114): {@link #authorize} puts a hold on the card (the
 * issuer's funds check, where a decline happens), then {@link #capture} moves the held money. shopflow
 * captures immediately after authorizing, but keeping the two distinct is what lets a declined auth and
 * a failed capture be different outcomes — and makes delayed capture a config change, not a reshape.
 *
 * CONTRACT (Walk 71): {@code orderRef} doubles as the processor-side IDEMPOTENCY KEY on every call, and
 * a real adapter MUST send it as one (Stripe: {@code Idempotency-Key} header; Razorpay: {@code receipt}).
 * The listener deliberately calls the gateway INSIDE the transaction that saves the payment row:
 * a crash between the gateway call and the commit, or a rebalance delivering the same event to two
 * consumers, retries the whole step — DB-side the eventId/status guards converge, but the gateway
 * sees a second authorize()/capture()/refund() for the same orderRef. Only processor-side dedupe keyed
 * on orderRef stops that second call from moving money twice. (The other ordering — commit first,
 * charge after — is worse: a crash then loses the charge entirely with a CAPTURED-looking row.)
 */
public interface PaymentGateway {

    record Result(boolean approved, String reason) {
        static Result ok() {
            return new Result(true, null);
        }

        static Result declined(String reason) {
            return new Result(false, reason);
        }
    }

    /** Hold funds on the card. This is the issuer's funds check — a decline (limit, etc.) happens HERE. */
    Result authorize(String orderRef, BigDecimal amount);

    /** Take a prior authorization's held funds. Capturing a valid hold rarely fails; a decline is a manual case. */
    Result capture(String orderRef, BigDecimal amount);

    /** Return a captured amount to the customer. Refunds rarely fail; when they do it is a manual case. */
    Result refund(String orderRef, BigDecimal amount);
}
