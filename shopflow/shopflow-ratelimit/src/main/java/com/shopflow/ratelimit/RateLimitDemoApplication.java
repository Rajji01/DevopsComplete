package com.shopflow.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Runnable demo so every algorithm can be exercised live.
 *   ./mvnw -pl shopflow-ratelimit spring-boot:run      (needs JDK 21)
 * then hammer the endpoint and watch the 429s:
 *   for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " -H 'X-Client-Id: alice' localhost:8086/api/ping; done; echo
 *
 * Switch the live algorithm without touching code (application.yml or a -D flag / env var), then restart:
 *   ratelimit.algorithm = FIXED_WINDOW | SLIDING_WINDOW_LOG | SLIDING_WINDOW_COUNTER | TOKEN_BUCKET | LEAKY_BUCKET
 *   ratelimit.backend   = LOCAL (in-memory, no Redis) | REDIS (distributed, Lua)
 *
 * JPA/Flyway are excluded: the parent pom gives every module a JPA + Postgres stack, but this demo has no
 * database, so the DataSource auto-config would otherwise fail startup. Rate limiting needs only web + Redis.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class RateLimitDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimitDemoApplication.class, args);
    }
}
