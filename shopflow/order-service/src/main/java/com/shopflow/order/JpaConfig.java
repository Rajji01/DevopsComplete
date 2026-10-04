package com.shopflow.order;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA also covers the shared outbox module. Kept out of the application class on purpose:
 * a @WebMvcTest slice would otherwise be forced to create repositories and an EntityManager.
 */
@Configuration
@EntityScan(basePackages = {"com.shopflow.order", "com.shopflow.outbox"})
@EnableJpaRepositories(basePackages = {"com.shopflow.order", "com.shopflow.outbox"})
class JpaConfig {
}
