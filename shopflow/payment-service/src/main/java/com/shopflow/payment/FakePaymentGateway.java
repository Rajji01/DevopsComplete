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
    public Result charge(String orderRef, BigDecimal amount) {
        return amount.compareTo(limit) > 0
                ? Result.declined("card limit exceeded (" + amount + " > " + limit + ")")
                : Result.ok();
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
