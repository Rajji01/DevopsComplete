package com.shopflow.payment;

import java.math.BigDecimal;

/**
 * The card processor (Razorpay, Stripe, ...). Behind an interface so the real one is a one-class change.
 *
 * CONTRACT (Walk 71): {@code orderRef} doubles as the processor-side IDEMPOTENCY KEY, and a real
 * adapter MUST send it as one (Stripe: {@code Idempotency-Key} header; Razorpay: {@code receipt}).
 * The listener deliberately calls the gateway INSIDE the transaction that saves the payment row:
 * a crash between the gateway call and the commit, or a rebalance delivering the same event to two
 * consumers, retries the whole step — DB-side the eventId/status guards converge, but the gateway
 * sees a second charge()/refund() for the same orderRef. Only processor-side dedupe keyed on
 * orderRef stops that second call from moving money twice. (The other ordering — commit first,
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

    Result charge(String orderRef, BigDecimal amount);

    /** Return a captured amount to the customer. Refunds rarely fail; when they do it is a manual case. */
    Result refund(String orderRef, BigDecimal amount);
}
