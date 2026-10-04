package com.shopflow.payment;

import java.math.BigDecimal;

/** The card processor (Razorpay, Stripe, ...). Behind an interface so the real one is a one-class change. */
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
}
