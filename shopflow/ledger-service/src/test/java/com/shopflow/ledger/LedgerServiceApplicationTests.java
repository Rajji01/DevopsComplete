package com.shopflow.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full context against H2: proves the Flyway migration applies and the entities pass
 * ddl-auto=validate (entity columns match the schema). The real-Postgres equivalent is
 * {@link PostgresSchemaValidationTest}, which runs in CI's postgres-smoke job (Walk 120).
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerServiceApplicationTests {

    @Test
    void contextLoads() {
        // reaching here = Flyway migrated + Hibernate validated the schema
    }
}
