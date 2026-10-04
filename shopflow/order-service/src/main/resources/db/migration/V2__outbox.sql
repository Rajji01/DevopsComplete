create table outbox_event (
    id             uuid         primary key,
    aggregate_type varchar(32)  not null,
    aggregate_id   varchar(64)  not null,
    event_type     varchar(64)  not null,
    payload        text         not null,
    created_at     timestamp    not null,
    published_at   timestamp
);

-- the relay always asks "oldest unpublished first"
-- (a PostgreSQL partial index "... where published_at is null" would be smaller, but H2 in tests cannot parse it)
create index idx_outbox_pending on outbox_event (published_at, created_at);
