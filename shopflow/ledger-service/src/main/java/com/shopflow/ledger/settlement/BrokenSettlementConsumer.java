package com.shopflow.ledger.settlement;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * ⚠️ THE ANTI-PATTERN — kept to be read and tested, NOT wired to Kafka (the original was a
 * {@code @KafkaListener(topics = "settlements", concurrency = "3")}; that annotation is shown in the comment
 * below, not applied, so it never runs in prod competing with {@link SettlementConsumer}). Every production
 * complaint traces to a line here. See docs/debugging/settlement-consumer-idempotency.md.
 *
 * <pre>
 * // original:
 * // {@literal @}KafkaListener(topics = "settlements", concurrency = "3")
 * </pre>
 *
 * What it TRIES to do: process each settlement once — debit the payer, credit the counterparty — and skip
 * trades it has already seen. What it actually does:
 *
 *  S1 (restart → double settle): {@code processed} is an in-memory {@link HashSet}. On a deploy/restart the
 *     process dies and the set is GONE; Kafka redelivers uncommitted offsets, the fresh set says "never
 *     seen it", and the trade settles again. The dedup memory did not survive the thing it had to survive.
 *
 *  S2 (more pods → more duplicates): each pod has its OWN HashSet. With {@code concurrency="3"} and several
 *     pods, a trade handled on pod A is unknown to pod B, so a rebalance/partition move re-settles it. The
 *     dedup state is local to a process that is one of many.
 *
 *  S3 (debit without credit): {@code debit} and {@code credit} are two SEPARATE transactions (there is no
 *     surrounding one here — see {@link SettlementLedger}). The debit commits; if the credit then throws
 *     (bad account, pod killed, DB blip) the money has left one side and never reached the other. Not atomic.
 *
 *  Hidden danger (concurrency="3"): a {@code HashSet} is NOT thread-safe, and three listener threads call
 *     this method at once. Two problems: (a) a concurrent {@code add}/{@code contains} can corrupt the set's
 *     internal state (lost entries, and historically even an infinite loop on resize); (b) even if it didn't,
 *     {@code contains}→settle→{@code add} is check-then-act — two threads with the SAME tradeId can both read
 *     "not present" before either adds, and both settle it. A plain set can't make dedup atomic.
 *
 * The one root cause under all four: the "have I done this trade?" truth is kept in volatile, per-process,
 * non-atomic memory. It belongs in the durable, shared, transactional store — the database. ({@link
 * SettlementConsumer} is the fix.)
 */
@Component
public class BrokenSettlementConsumer {

    private final Set<String> processed = new HashSet<>();
    private final SettlementLedger ledger;

    public BrokenSettlementConsumer(SettlementLedger ledger) {
        this.ledger = ledger;
    }

    public void onMessage(SettlementEvent e) {
        if (processed.contains(e.tradeId())) {
            return; // in-memory, per-pod, not thread-safe — see the class javadoc for why this fails
        }
        ledger.debit(e.account(), e.amountMinor());        // own transaction — commits immediately...
        ledger.credit(e.counterparty(), e.amountMinor());  // ...own transaction — if this throws, debit stays (S3)
        processed.add(e.tradeId());
    }
}
