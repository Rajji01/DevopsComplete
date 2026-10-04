package com.shopflow.inventory.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Redis values as JSON instead of JDK serialization. JDK serialization needs Serializable classes,
 * breaks on every class change, and is unreadable in redis-cli; JSON (with a type hint) is none of that.
 * Only applied when spring.cache.type=redis; the 'simple' cache used locally/in tests ignores it.
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    /**
     * Fail open: if Redis is down, a cache miss is logged and the request goes to the database
     * instead of failing. A cache must never be a hard dependency of a read path.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    @Bean
    RedisCacheManagerBuilderCustomizer jsonCacheValues() {
        var serializer = new GenericJackson2JsonRedisSerializer();
        return builder -> builder.cacheDefaults(
                builder.cacheDefaults().serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }
}
