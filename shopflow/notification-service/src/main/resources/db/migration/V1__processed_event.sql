create table processed_event (
    event_id     uuid        primary key,
    order_ref    varchar(64) not null,
    processed_at timestamp   not null
);
