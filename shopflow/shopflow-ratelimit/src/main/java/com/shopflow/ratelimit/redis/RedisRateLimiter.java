package com.shopflow.ratelimit.redis;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.shopflow.ratelimit.RateLimitAlgorithm;
import com.shopflow.ratelimit.RateLimitResult;
import com.shopflow.ratelimit.RateLimitRule;
import com.shopflow.ratelimit.RateLimiter;

/**
 * The DISTRIBUTED backend: the decision lives in Redis, so every app pod shares one source of truth and
 * one clock. Each algorithm is a Lua script (see {@code resources/ratelimit/*.lua}); the whole script runs
 * atomically, which is the point — doing GET-then-INCR from Java would open a race between the read and the
 * write that lets two pods both think they are under the limit. One Java class serves all five algorithms;
 * only the script and its ARGV differ.
 *
 * Every script returns the same shape — {@code [allowed, remaining, retryAfterMs]} as a {@code List<Long>}
 * (the Spring Cloud Gateway recipe for Lua rate limiters).
 */
public final class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;
    private final RateLimitAlgorithm algorithm;
    private final RateLimitRule rule;
    private final String keyPrefix;
    private final RedisScript<List> script;

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitAlgorithm algorithm,
                            RateLimitRule rule, String keyPrefix) {
        this.redis = redis;
        this.algorithm = algorithm;
        this.rule = rule;
        this.keyPrefix = keyPrefix;
        var s = new DefaultRedisScript<List>();
        s.setLocation(new ClassPathResource("ratelimit/" + scriptFile(algorithm)));
        s.setResultType(List.class);
        this.script = s;
    }

    private static String scriptFile(RateLimitAlgorithm a) {
        return switch (a) {
            case FIXED_WINDOW -> "fixed_window.lua";
            case SLIDING_WINDOW_LOG -> "sliding_window_log.lua";
            case SLIDING_WINDOW_COUNTER -> "sliding_window_counter.lua";
            case TOKEN_BUCKET -> "token_bucket.lua";
            case LEAKY_BUCKET -> "leaky_bucket.lua";
            case GCRA -> "gcra.lua";
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult tryAcquire(String key, int permits) {
        List<String> keys = List.of(keyPrefix + ":" + key);
        Object[] argv = switch (algorithm) {
            // bucket algos (and GCRA) are parameterised by capacity + rate...
            case TOKEN_BUCKET, LEAKY_BUCKET, GCRA ->
                    new Object[]{num(rule.capacity()), num(rule.refillPerSecond()), Integer.toString(permits)};
            // ...the log also needs a unique id so each permit is a distinct ZSET member...
            case SLIDING_WINDOW_LOG ->
                    new Object[]{num(rule.limit()), num(rule.windowMillis()), Integer.toString(permits),
                            UUID.randomUUID().toString()};
            // ...the window algos just need limit + window.
            default ->
                    new Object[]{num(rule.limit()), num(rule.windowMillis()), Integer.toString(permits)};
        };

        List<Long> r = (List<Long>) redis.execute(script, keys, argv);
        boolean allowed = r.get(0) == 1L;
        long remaining = r.get(1);
        long retryMs = r.get(2);
        long ceiling = switch (algorithm) {
            case TOKEN_BUCKET, LEAKY_BUCKET, GCRA -> rule.capacity();
            default -> rule.limit();
        };
        return allowed
                ? RateLimitResult.allow(remaining, ceiling)
                : RateLimitResult.deny(remaining, ceiling, retryMs);
    }

    // Lua tonumber() parses either; send whole numbers without a trailing ".0" so logs/keys stay clean.
    private static String num(double d) {
        return (d == Math.rint(d) && !Double.isInfinite(d)) ? Long.toString((long) d) : Double.toString(d);
    }
}
