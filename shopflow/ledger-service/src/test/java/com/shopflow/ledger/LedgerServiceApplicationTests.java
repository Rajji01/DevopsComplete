package com.shopflow.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full context against H2: proves the Flyway migration applies and the entities pass
 * ddl-auto=validate (entity columns match the schema). The real-Postgres equivalent is increment 5
 * (add ledger to the CI postgres-smoke job).
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerServiceApplicationTests {

    @Test
    void contextLoads() {
        // reaching here = Flyway migrated + Hibernate validated the schema
    }
}
