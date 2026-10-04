-- idempotent consumer of payments.events
create table processed_event (
    event_id     uuid      primary key,
    processed_at timestamp not null
);
