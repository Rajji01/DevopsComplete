package com.shopflow.payment;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic stand-in: approves everything up to a limit, so the failure path is testable. */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private final BigDecimal limit;
    private final BigDecimal refundLimit;

    public FakePaymentGateway(@Value("${payment.card-limit:100000}") BigDecimal limit,
                              @Value("${payment.refund-limit:100000000}") BigDecimal refundLimit) {
        this.limit = limit;
        this.refundLimit = refundLimit;
    }

    @Override
    public Result authorize(String orderRef, BigDecimal amount) {
        // The issuer's funds check lives here: this is the step that declines (card limit, no funds).
        return amount.compareTo(limit) > 0
                ? Result.declined("card limit exceeded (" + amount + " > " + limit + ")")
                : Result.ok();
    }

    @Override
    public Result capture(String orderRef, BigDecimal amount) {
        // Capturing a hold we just authorized always succeeds in the fake. A real processor can decline
        // (expired/voided auth); that path maps to Payment.failCapture and PaymentFailed if it ever does.
        return Result.ok();
    }

    @Override
    public Result refund(String orderRef, BigDecimal amount) {
        // Refunds usually succeed; the default limit is effectively infinite. A low payment.refund-limit
        // lets a test force the decline path so PaymentRefundFailed is exercised.
        return amount.compareTo(refundLimit) > 0
                ? Result.declined("refund above the processor limit (" + amount + " > " + refundLimit + ")")
                : Result.ok();
    }
}
