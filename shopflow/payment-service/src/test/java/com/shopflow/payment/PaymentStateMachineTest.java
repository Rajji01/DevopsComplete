package com.shopflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The payment lifecycle guard (Walk 114): every mutation goes through transitionTo, so a replayed or
 * out-of-order saga event cannot rewrite a terminal state. These are pure-Java (no Spring) assertions
 * of the legal graph AUTHORIZED -> {CAPTURED, FAILED}, CAPTURED -> {REFUNDED}, FAILED/REFUNDED -> {}.
 */
class PaymentStateMachineTest {

    private static Payment authorized() {
        return Payment.authorized(UUID.randomUUID(), "ord-" + UUID.randomUUID(), new BigDecimal("100.00"));
    }

    @Test
    void authorizeThenCaptureThenRefundIsTheHappyPath() {
        Payment p = authorized();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.AUTHORIZED);

        p.capture();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.CAPTURED);

        p.refund();
        assertThat(p.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void authorizedCannotBeRefundedDirectly() {
        // an auth hold has no captured money to return — you void it, not refund it
        Payment p = authorized();
        assertThatThrownBy(p::refund)
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("AUTHORIZED -> REFUNDED");
        assertThat(p.getStatus()).isEqualTo(Payment.Status.AUTHORIZED);
    }

    @Test
    void failedCaptureIsTerminalAndRecordsReason() {
        Payment p = authorized();
        p.failCapture("auth expired");
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("auth expired");

        // FAILED is terminal: it cannot later be captured or refunded
        assertThatThrownBy(p::capture).isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(p::refund).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void aDeclinedAuthorizationIsAFailedTerminalRow() {
        Payment p = Payment.failed(UUID.randomUUID(), "ord-x", BigDecimal.ZERO, "card limit exceeded");
        assertThat(p.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThatThrownBy(p::capture).isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void capturedCannotBeCapturedAgain() {
        Payment p = authorized();
        p.capture();
        // a replayed capture must not silently re-run: CAPTURED -> CAPTURED is not legal
        assertThatThrownBy(p::capture)
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("CAPTURED -> CAPTURED");
    }

    @Test
    void refundedIsTerminalSoADuplicateRefundThrows() {
        Payment p = authorized();
        p.capture();
        p.refund();
        assertThatThrownBy(p::refund)
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .hasMessageContaining("REFUNDED -> REFUNDED");
    }
}
