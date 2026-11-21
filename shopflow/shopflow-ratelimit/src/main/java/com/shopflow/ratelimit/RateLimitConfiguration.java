package com.shopflow.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Exposes the single active {@link RateLimiter} bean, built from {@link RateLimitProperties}. The clock is
 * the real system clock here; tests build limiters directly with a fake clock instead.
 *
 * Both collaborators are {@code ObjectProvider} (not hard dependencies) so this config can be @Imported
 * into a thin context that has neither:
 *   - {@code StringRedisTemplate} is only resolved when {@code backend=REDIS}, so backend=LOCAL starts
 *     fine with no Redis;
 *   - {@code MeterRegistry} falls back to a throwaway {@link SimpleMeterRegistry} when metrics aren't on
 *     the context (e.g. a consumer's @WebMvcTest slice imports this via its SecurityConfig). In a real
 *     service or the demo app the autoconfigured registry is present and used instead.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties props,
                                   ObjectProvider<StringRedisTemplate> redis,
                                   ObjectProvider<MeterRegistry> metrics) {
        return RateLimiterFactory.create(props, System::currentTimeMillis, redis,
                metrics.getIfAvailable(SimpleMeterRegistry::new));
    }
}
