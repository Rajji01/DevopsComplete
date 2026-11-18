package com.shopflow.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs ONLY in CI's postgres-smoke job (PG_SMOKE=true + a real Postgres service container);
 * skipped everywhere else. See order-service's PostgresSchemaValidationTest for the rationale:
 * H2 forgives type mismatches (Instant vs timestamp) that Postgres + ddl-auto=validate may not.
 */
@SpringBootTest
@ActiveProfiles("pg")
@EnabledIfEnvironmentVariable(named = "PG_SMOKE", matches = "true")
class PostgresSchemaValidationTest {

    @Test
    void contextStartsAgainstRealPostgres() {
        // reaching this line means Flyway migrated (incl. the seed) and validate passed on Postgres
    }
}
