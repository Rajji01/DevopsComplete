package com.shopflow.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Exposes the single active {@link RateLimiter} bean, built from {@link RateLimitProperties}. The clock is
 * the real system clock here; tests build limiters directly with a fake clock instead.
 *
 * {@code ObjectProvider<StringRedisTemplate>} (not a hard dependency) so the app starts fine with
 * {@code backend=LOCAL} even if Redis is unreachable — the template is only resolved when backend=REDIS.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties props,
                                   ObjectProvider<StringRedisTemplate> redis,
                                   MeterRegistry metrics) {
        return RateLimiterFactory.create(props, System::currentTimeMillis, redis, metrics);
    }
}
