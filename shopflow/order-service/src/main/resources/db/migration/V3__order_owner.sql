-- Expand step: nullable first, backfill, then NOT NULL. All in one migration here because the
-- table is new; on a live table the NOT NULL would come in a later release (expand -> contract).
alter table orders add column customer_id varchar(100);
update orders set customer_id = 'unknown' where customer_id is null;
alter table orders alter column customer_id set not null;

-- list endpoint: "my orders, newest first"
create index idx_orders_customer_created on orders (customer_id, created_at desc);
-- reconciler: "FAILED orders older than X"
create index idx_orders_status_updated on orders (status, updated_at);
