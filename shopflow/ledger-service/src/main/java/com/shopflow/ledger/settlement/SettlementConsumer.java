package com.shopflow.ledger.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * ✅ THE FIX. Same job as {@link BrokenSettlementConsumer}, but the "have I settled this trade?" truth and
 * the money movement both live in the DATABASE, inside ONE transaction. That single move dissolves all four
 * problems:
 *
 *  S1 (restart): the dedup row is in {@code processed_settlement}, so it SURVIVES a restart. A redelivered
 *     trade finds its row and is skipped. (Offsets commit only after the handler returns, so a crash mid-way
 *     just redelivers — safely, since we're idempotent.)
 *  S2 (many pods): every pod reads the SAME table, so a trade settled on any pod is "seen" by all. One
 *     consumer group means the pods SHARE the partitions too, rather than each reprocessing everything.
 *  S3 (half-settle): {@code @Transactional} makes debit + credit + the dedup insert commit ALL-OR-NOTHING.
 *     If the credit throws, the debit and the dedup row roll back with it — no money stranded, and the trade
 *     is NOT marked done, so a later redelivery retries it cleanly.
 *  Thread-safety: no shared in-memory set. Concurrency is the database's job — the {@code trade_id} PRIMARY
 *     KEY makes "settle once" a constraint, so even two threads racing the same trade end with one row (the
 *     loser's insert violates the key, its transaction — debit/credit included — rolls back).
 *
 * The real Kafka path takes a {@code String} payload and parses it (ledger's convention, same as
 * {@link com.shopflow.ledger.saga.LedgerPostingListener}), so it works over a real broker; {@link #settle}
 * holds the typed, transactional logic shared by the listener, the REST demo endpoint, and the tests.
 */
@Component
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final ProcessedSettlementRepository processed;
    private final SettlementLedger ledger;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public SettlementConsumer(ProcessedSettlementRepository processed, SettlementLedger ledger,
                              MeterRegistry meterRegistry, ObjectMapper objectMapper) {
        this.processed = processed;
        this.ledger = ledger;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    // one consumer group: the pods share the partitions; offsets commit after this returns (at-least-once).
    // @Transactional wraps the parse + settle so the whole handling of one record is atomic.
    @KafkaListener(topics = "settlements", groupId = "ledger-settlement")
    @Transactional
    public void onSettlement(String payload) throws Exception {
        settle(objectMapper.readValue(payload, SettlementEvent.class));
    }

    /** The idempotent, atomic settle — shared by the Kafka listener, the REST demo, and the tests. */
    @Transactional
    public void settle(SettlementEvent e) {
        if (processed.existsById(e.tradeId())) {              // durable + shared dedup: the DB is the truth
            meterRegistry.counter("settlements.duplicates").increment();
            log.info("settlement {} already processed, skipping", e.tradeId());
            return;
        }
        ledger.debit(e.account(), e.amountMinor());           // debit + credit + the dedup insert all join
        ledger.credit(e.counterparty(), e.amountMinor());     // this one @Transactional -> atomic (no S3)
        processed.save(new ProcessedSettlement(e.tradeId())); // unique PK = at-most-once, even under a race
        meterRegistry.counter("settlements.processed").increment();
    }
}
