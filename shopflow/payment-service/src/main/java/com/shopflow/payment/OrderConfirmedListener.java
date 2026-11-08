package com.shopflow.payment;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Saga step 2 (choreography): nobody tells payment-service what to do; it reacts to OrderConfirmed.
 * Charge + payment row + outgoing PaymentCaptured/PaymentFailed event are one local transaction
 * (outbox), so a crash can never leave a charge without its event or an event without its charge.
 */
@Component
public class OrderConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PriceList priceList;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OrderConfirmedListener(PaymentRepository payments, PaymentGateway gateway, PriceList priceList,
                                  OutboxPublisher outbox, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.payments = payments;
        this.gateway = gateway;
        this.priceList = priceList;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "orders.events", groupId = "payment-service")
    @Transactional
    public void onOrderEvent(String payload) throws Exception {
        OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
        switch (event.type()) {
            case "OrderConfirmed" -> charge(event);
            case "OrderRefundPending" -> refund(event);
            default -> { /* OrderRejected, OrderCancelled, OrderPaid, OrderRefunded: not ours */ }
        }
    }

    private void charge(OrderEvent event) {
        if (payments.existsById(event.eventId())) {
            meterRegistry.counter("payments.duplicates").increment();
            return; // redelivery: already charged (or declined) for this exact event
        }
        BigDecimal amount;
        PaymentGateway.Result result;
        var price = priceList.priceOf(event.sku());
        if (price.isPresent()) {
            amount = price.get().multiply(BigDecimal.valueOf(event.quantity()));
            result = gateway.charge(event.orderRef(), amount);
        } else {
            // A sku inventory sells but we cannot price must never be charged a made-up amount
            // (the old 999.00 fallback did exactly that — Walk 42). Decline: the FAILED payment +
            // PaymentFailed event flow through the normal path, so the order cancels and the
            // reserved stock is released instead of the customer paying a wrong price.
            amount = BigDecimal.ZERO;
            result = PaymentGateway.Result.declined("no price for sku " + event.sku());
        }

        Payment payment = new Payment(event.eventId(), event.orderRef(), amount,
                result.approved() ? Payment.Status.CAPTURED : Payment.Status.FAILED, result.reason());
        payments.save(payment);

        PaymentEvent out = result.approved()
                ? PaymentEvent.captured(event.orderRef(), amount)
                : PaymentEvent.failed(event.orderRef(), amount, result.reason());
        outbox.publish(PaymentEvent.TOPIC, "Payment", event.orderRef(), out.type(), out);

        meterRegistry.counter("payments", "status", payment.getStatus().name()).increment();
        log.info("payment for order {}: {} {}", event.orderRef(), payment.getStatus(), amount);
    }

    /**
     * Idempotency here is by STATE, not by eventId: the payment row already exists (keyed by the
     * original OrderConfirmed event), so we look it up by orderRef and only refund a CAPTURED one.
     * A redelivered OrderRefundPending finds it already REFUNDED and does nothing.
     */
    private void refund(OrderEvent event) {
        var payment = payments.findByOrderRef(event.orderRef()).orElse(null);
        if (payment == null) {
            // No payment row at all for a REFUND_PENDING order: upstream data problem, and silence
            // here would leave the stuck order a mystery (Walk 42). OrdersStuckConfirmed will flag
            // the order; this log is what tells the human WHY no refund ever happened.
            log.warn("refund requested for order {} but no payment row exists — needs a human", event.orderRef());
            return;
        }
        if (payment.getStatus() != Payment.Status.CAPTURED) {
            // REFUNDED = duplicate OrderRefundPending after a successful refund (normal, idempotent
            // no-op). FAILED = a refund for a payment that never captured (state-machine violation
            // upstream) — still a no-op, but one worth seeing in the log.
            log.info("refund for order {} skipped: payment is {}", event.orderRef(), payment.getStatus());
            return;
        }
        var result = gateway.refund(payment.getOrderRef(), payment.getAmount());
        if (result.approved()) {
            payment.markRefunded();
            outbox.publish(PaymentEvent.TOPIC, "Payment", payment.getOrderRef(),
                    "PaymentRefunded", PaymentEvent.refunded(payment.getOrderRef(), payment.getAmount()));
            meterRegistry.counter("payments", "status", "REFUNDED").increment();
            log.info("refunded order {} amount {}", payment.getOrderRef(), payment.getAmount());
        } else {
            // Refund declined by the processor. Do NOT mark REFUNDED (the money did not come back);
            // the payment stays CAPTURED so a redelivered OrderRefundPending retries. Emit
            // PaymentRefundFailed so the order stays REFUND_PENDING and the OrdersStuckConfirmed
            // alert (which counts REFUND_PENDING) pages a human — we never fake a refund that failed.
            outbox.publish(PaymentEvent.TOPIC, "Payment", payment.getOrderRef(),
                    "PaymentRefundFailed", PaymentEvent.refundFailed(payment.getOrderRef(), payment.getAmount(), result.reason()));
            meterRegistry.counter("payments.refund.failed").increment();
            log.warn("refund DECLINED for order {}: {} (stays CAPTURED; order stays REFUND_PENDING)",
                    payment.getOrderRef(), result.reason());
        }
    }
}
