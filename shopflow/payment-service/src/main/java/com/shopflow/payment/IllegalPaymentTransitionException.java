package com.shopflow.payment;

/**
 * A payment was asked to move to a status its current status does not allow (e.g. refunding a payment
 * that only authorized but never captured, or capturing one that already failed). The legal graph lives
 * in {@link Payment.Status#canMoveTo} — this is thrown when a caller violates it, so an out-of-order or
 * replayed saga event corrupts nothing: it fails loud instead of silently rewriting a terminal state.
 */
public class IllegalPaymentTransitionException extends IllegalStateException {

    IllegalPaymentTransitionException(Payment.Status from, Payment.Status to) {
        super("illegal payment transition " + from + " -> " + to);
    }
}
