package com.shopflow.ledger.saga;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopflow.ledger.LedgerService;
import com.shopflow.ledger.domain.JournalEntryStatus;
import com.shopflow.ledger.domain.Money;
import com.shopflow.ledger.domain.PostingDirection;
import com.shopflow.ledger.domain.PostingLine;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Saga step (choreography): the ledger reacts to payment-service's events and records each money
 * movement as a balanced double entry. Nobody orchestrates it — the same {@code payments.events}
 * topic order-service consumes to drive the order's state, the ledger consumes to keep the books.
 *
 * <h3>Idempotency — the inbox, without an inbox table</h3>
 * This is an idempotent consumer, but it needs NO separate {@code processed_event} table. The
 * natural idempotency key — the payment {@code eventId} — is passed straight into
 * {@link LedgerService#record} as the journal entry's {@code idempotencyKey}, which has a UNIQUE
 * constraint. A redelivered event therefore finds the entry already posted and returns it: exactly
 * one journal entry per source event, which is precisely what a dedicated inbox table would buy —
 * so adding one would be redundant state to keep in sync. (When a consumer's effect is NOT a single
 * keyed DB write — e.g. sending an email — a {@code processed_event} row is the right inbox; here
 * the business write already is one.)
 *
 * <h3>Why this method is deliberately NOT @Transactional</h3>
 * {@code record()} is non-transactional on purpose (Walk 99): it does the entry+postings write in
 * its own {@code TransactionTemplate} and catches the unique-key race OUTSIDE that transaction.
 * Wrapping this listener in {@code @Transactional} would enlist {@code record()}'s write into the
 * listener's transaction, so a concurrent-duplicate's {@code DataIntegrityViolationException} would
 * mark the OUTER transaction rollback-only and the recovery re-fetch would die at commit with
 * {@code UnexpectedRollbackException}. Letting the listener stay non-transactional keeps
 * {@code record()}'s race recovery intact. At-least-once redelivery on a mid-processing crash is
 * safe for the same reason: the key is the guard, not a transaction boundary here.
 */
@Component
public class LedgerPostingListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingListener.class);

    private final LedgerService ledger;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public LedgerPostingListener(LedgerService ledger, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = PaymentEvent.TOPIC, groupId = "ledger-service")
    public void onPaymentEvent(String payload) throws Exception {
        PaymentEvent event = objectMapper.readValue(payload, PaymentEvent.class);
        switch (event.type()) {
            case "PaymentCaptured" -> post(event, PostingDirection.DEBIT);   // cash in: DR CASH / CR SALES_REVENUE
            case "PaymentRefunded" -> post(event, PostingDirection.CREDIT);  // cash out: reverse of a capture
            // PaymentFailed / PaymentRefundFailed move no money, so there is nothing to post. A
            // redelivery of these is a no-op already, so they need no idempotency bookkeeping.
            default -> log.debug("ledger ignores payment event type {}", event.type());
        }
    }

    /**
     * Post the capture (or its reverse). {@code cashDirection} is DEBIT for a capture (cash goes
     * up) and CREDIT for a refund (cash goes down); SALES_REVENUE always takes the opposite leg, so
     * the two always balance.
     */
    private void post(PaymentEvent event, PostingDirection cashDirection) {
        if (event.amount() == null) {
            // a captured/refunded event with no amount is a producer bug; fail loudly (DLT) rather
            // than post a zero or guess — the books must never absorb an unexplained movement.
            throw new IllegalArgumentException(
                    "payment event " + event.eventId() + " (" + event.type() + ") has no amount");
        }
        Money amount = Money.fromMajor(event.amount(), LedgerAccounts.CURRENCY);
        PostingDirection revenueDirection = (cashDirection == PostingDirection.DEBIT)
                ? PostingDirection.CREDIT
                : PostingDirection.DEBIT;

        List<PostingLine> lines = List.of(
                new PostingLine(LedgerAccounts.CASH, cashDirection, amount),
                new PostingLine(LedgerAccounts.SALES_REVENUE, revenueDirection, amount));

        // eventId -> idempotencyKey (the inbox guard); orderRef -> reference (what recon groups by).
        // Born CAPTURED, not INITIATED: a PaymentCaptured/PaymentRefunded event records money that has
        // ALREADY moved. Reconciliation drives the matched entry CAPTURED -> SETTLED -> RECONCILED (Walk 116).
        ledger.record(event.eventId().toString(), event.orderRef(), LedgerAccounts.CURRENCY, lines,
                JournalEntryStatus.CAPTURED);

        meterRegistry.counter("ledger.postings", "type", event.type()).increment();
        log.info("ledger posted {} for order {}: {} {} (event {})",
                event.type(), event.orderRef(), amount.minorUnits(), amount.currency(), event.eventId());
    }
}
