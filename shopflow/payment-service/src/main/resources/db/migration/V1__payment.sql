create table payment (
    event_id       uuid           primary key,   -- id of the OrderConfirmed event: idempotency
    order_ref      varchar(64)    not null unique,
    amount         numeric(12,2)  not null,
    status         varchar(16)    not null,
    failure_reason varchar(255),
    created_at     timestamp      not null
);

create table outbox_event (
    id             uuid         primary key,
    topic          varchar(128) not null,
    aggregate_type varchar(32)  not null,
    aggregate_id   varchar(64)  not null,
    event_type     varchar(64)  not null,
    payload        text         not null,
    created_at     timestamp    not null,
    published_at   timestamp
);
create index idx_outbox_pending on outbox_event (published_at, created_at);
