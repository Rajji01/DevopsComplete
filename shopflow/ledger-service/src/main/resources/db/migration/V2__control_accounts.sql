-- Platform control accounts for the payment flow, seeded as reference data in the schema itself.
--
-- Why a migration and not an ApplicationRunner (Walk 106): @KafkaListener containers are
-- SmartLifecycle beans started during context finishRefresh(), which runs BEFORE Spring Boot's
-- ApplicationRunner callbacks. So a runner-based seed could lose a startup race — the ledger
-- consumer could pick up a PaymentCaptured and hit accounts.require('CASH') before the runner had
-- created it, throwing AccountNotFoundException and risking a dead-letter on a cold start. Flyway
-- migrations run during context initialization (before any listener consumes), so seeding here
-- guarantees the accounts exist before the first event is processed.
--
-- CASH: ASSET, increased by a debit when a customer pays (DR on capture).
-- SALES_REVENUE: REVENUE, increased by a credit on capture (CR). A refund reverses both.
insert into account (code, type, currency, created_at) values
    ('CASH',          'ASSET',   'INR', CURRENT_TIMESTAMP),
    ('SALES_REVENUE', 'REVENUE', 'INR', CURRENT_TIMESTAMP);
