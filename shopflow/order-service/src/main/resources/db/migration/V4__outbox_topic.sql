-- the outbox is now shared code serving several topics: each row says where it goes
alter table outbox_event add column topic varchar(128) not null default 'orders.events';
alter table outbox_event alter column topic drop default;
