package com.shopflow.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs ONLY in CI's postgres-smoke job (PG_SMOKE=true + a real Postgres service container);
 * skipped everywhere else. See order-service's PostgresSchemaValidationTest for the rationale:
 * H2 forgives type mismatches (Instant vs timestamptz, numeric precision) that Postgres +
 * ddl-auto=validate may not. The ledger's money columns make this parity worth having — a column
 * that validates on H2 but not on Postgres would only surface at deploy time without this test.
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
