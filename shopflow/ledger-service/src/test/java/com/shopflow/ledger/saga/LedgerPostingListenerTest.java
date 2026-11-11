package com.shopflow.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.ledger.JournalEntryRepository;
import com.shopflow.ledger.LedgerService;

/**
 * The inbound saga step: a PaymentCaptured/PaymentRefunded event on payments.events becomes a
 * balanced double entry, exactly once per source event. The listener method is called directly
 * (no broker — see application-test.yml auto-startup:false); the Kafka wiring is Spring's, the
 * posting + idempotency is ours and is what these tests pin.
 *
 * The CASH + SALES_REVENUE accounts are seeded at startup by {@link LedgerAccounts}, so they
 * already exist here. @Transactional rolls each test back, keeping derived balances isolated.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LedgerPostingListenerTest {

    @Autowired
    private LedgerPostingListener listener;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JournalEntryRepository journalEntries;

    private static String json(UUID eventId, String type, String orderRef, String amount) {
        // a real payments.events payload, including fields the ledger does not read (failureReason,
        // occurredAt) to prove @JsonIgnoreProperties lets the consumer ignore them
        return """
                {"eventId":"%s","type":"%s","orderRef":"%s","amount":%s,"failureReason":null,"occurredAt":"2026-11-11T10:00:00Z"}
                """.formatted(eventId, type, orderRef, amount);
    }

    @Test
    void capturePostsBalancedDoubleEntry_debitCashCreditRevenue() throws Exception {
        listener.onPaymentEvent(json(UUID.randomUUID(), "PaymentCaptured", "order-1", "749.99"));

        // 749.99 rupees -> 74999 paise; DR CASH (asset up), CR SALES_REVENUE (revenue up)
        assertThat(ledger.balanceOf(LedgerAccounts.CASH).minorUnits()).isEqualTo(74999L);
        assertThat(ledger.balanceOf(LedgerAccounts.SALES_REVENUE).minorUnits()).isEqualTo(-74999L);
        // the whole point of double entry: all balances sum to zero (money moved, not created)
        assertThat(ledger.balanceOf(LedgerAccounts.CASH).minorUnits()
                + ledger.balanceOf(LedgerAccounts.SALES_REVENUE).minorUnits()).isZero();
    }

    @Test
    void redeliveredCaptureIsPostedOnce_inboxGuardIsTheEventId() throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = json(eventId, "PaymentCaptured", "order-2", "100.00");

        listener.onPaymentEvent(payload);
        listener.onPaymentEvent(payload);   // at-least-once redelivery of the SAME event
        listener.onPaymentEvent(payload);

        // exactly one journal entry for the event, and CASH moved exactly once
        assertThat(journalEntries.findByIdempotencyKey(eventId.toString())).isPresent();
        assertThat(ledger.balanceOf(LedgerAccounts.CASH).minorUnits()).isEqualTo(10000L);
    }

    @Test
    void refundReversesTheCapture_netZero() throws Exception {
        listener.onPaymentEvent(json(UUID.randomUUID(), "PaymentCaptured", "order-3", "500.00"));
        listener.onPaymentEvent(json(UUID.randomUUID(), "PaymentRefunded", "order-3", "500.00"));

        // capture then refund (distinct event ids -> two entries) leaves the books flat
        assertThat(ledger.balanceOf(LedgerAccounts.CASH).minorUnits()).isZero();
        assertThat(ledger.balanceOf(LedgerAccounts.SALES_REVENUE).minorUnits()).isZero();
    }

    @Test
    void nonMoneyMovingEventsPostNothing() throws Exception {
        long before = journalEntries.count();

        listener.onPaymentEvent(json(UUID.randomUUID(), "PaymentFailed", "order-4", "999.00"));
        listener.onPaymentEvent(json(UUID.randomUUID(), "PaymentRefundFailed", "order-4", "999.00"));

        // a declined payment / failed refund moves no money, so the ledger stays untouched
        assertThat(journalEntries.count()).isEqualTo(before);
    }
}
