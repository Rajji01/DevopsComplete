#!/bin/sh
# Runs once, on the first start of an empty Postgres data directory.
# Database-per-service: each service gets its own DB and user and cannot read the other's tables.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE USER orders WITH PASSWORD '${ORDERS_DB_PASSWORD}';
    CREATE DATABASE orders OWNER orders;
    CREATE USER inventory WITH PASSWORD '${INVENTORY_DB_PASSWORD}';
    CREATE DATABASE inventory OWNER inventory;
    CREATE USER notifications WITH PASSWORD '${NOTIFICATIONS_DB_PASSWORD}';
    CREATE DATABASE notifications OWNER notifications;
    CREATE USER payments WITH PASSWORD '${PAYMENTS_DB_PASSWORD}';
    CREATE DATABASE payments OWNER payments;
EOSQL
