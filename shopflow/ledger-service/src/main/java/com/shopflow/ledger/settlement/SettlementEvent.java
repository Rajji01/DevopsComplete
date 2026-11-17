package com.shopflow.ledger.settlement;

/**
 * A settled trade: move {@code amountMinor} from {@code account} to {@code counterparty}. {@code tradeId}
 * is the business idempotency key — the SAME trade can be delivered more than once (Kafka is at-least-once,
 * and a redelivery happens on any consumer restart/rebalance), so every consumer here keys dedup on it.
 */
public record SettlementEvent(String tradeId, String account, String counterparty,
                              long amountMinor, String currency) {
}
