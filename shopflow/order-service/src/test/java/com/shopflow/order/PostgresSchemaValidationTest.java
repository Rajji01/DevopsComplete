package com.shopflow.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs ONLY in CI's postgres-smoke job (PG_SMOKE=true + a real Postgres service container);
 * skipped everywhere else. Every other test validates the schema against H2, but production
 * runs Postgres — and the two engines disagree on types H2 forgives (Walk 57: Hibernate 6 maps
 * Instant to TIMESTAMP_UTC, which ddl-auto=validate can reject against a plain `timestamp`
 * column on the real engine). Booting the full context here checks Flyway's DDL and Hibernate's
 * validation against the engine the compose/k8s stacks actually use.
 */
@SpringBootTest
@ActiveProfiles("pg")
@EnabledIfEnvironmentVariable(named = "PG_SMOKE", matches = "true")
class PostgresSchemaValidationTest {

    @Test
    void contextStartsAgainstRealPostgres() {
        // reaching this line means Flyway migrated and ddl-auto=validate passed on Postgres
    }
}
