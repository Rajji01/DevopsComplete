package com.shopflow.payment;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic stand-in: approves everything up to a limit, so the failure path is testable. */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private final BigDecimal limit;

    public FakePaymentGateway(@Value("${payment.card-limit:100000}") BigDecimal limit) {
        this.limit = limit;
    }

    @Override
    public Result charge(String orderRef, BigDecimal amount) {
        return amount.compareTo(limit) > 0
                ? Result.declined("card limit exceeded (" + amount + " > " + limit + ")")
                : Result.ok();
    }
}
