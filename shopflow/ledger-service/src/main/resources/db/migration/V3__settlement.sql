-- Settlement demo tables (teaching: the idempotent-consumer done WRONG vs RIGHT — see
-- com.shopflow.ledger.settlement and docs/debugging/settlement-consumer-idempotency.md).
--
--  settlement_balance   : the per-account running balance each settlement debits/credits.
--  processed_settlement : the DURABLE, SHARED dedup record — exactly one row per trade_id. This is the
--                         "truth" the broken consumer tried to keep in an in-memory HashSet. In the DB it
--                         survives restarts (fixes S1), is shared by every pod (fixes S2), and its PRIMARY
--                         KEY makes "settle this trade once" a constraint the database enforces, even under
--                         concurrency (fixes the HashSet thread-safety hole).
create table settlement_balance (
    account       varchar(64) primary key,
    balance_minor bigint      not null
);

create table processed_settlement (
    trade_id     varchar(64) primary key,
    processed_at timestamp   not null
);
