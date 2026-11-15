package com.shopflow.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about rate limiting is config, not code — flip the algorithm or the backend and restart, no
 * recompile. Bound from the {@code ratelimit.*} prefix (see application.yml).
 *
 *   ratelimit.algorithm         FIXED_WINDOW | SLIDING_WINDOW_LOG | SLIDING_WINDOW_COUNTER | TOKEN_BUCKET | LEAKY_BUCKET
 *   ratelimit.backend           LOCAL (in-memory, per-pod) | REDIS (distributed, shared across pods)
 *   ratelimit.limit / .window   used by the window algorithms ("limit per window")
 *   ratelimit.capacity / .refill-per-second   used by the bucket algorithms
 *   ratelimit.fail-open         on backend error, allow (true) or deny (false)
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {

    public enum Backend { LOCAL, REDIS }

    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
    private Backend backend = Backend.LOCAL;
    private long limit = 10;
    private Duration window = Duration.ofSeconds(10);
    private long capacity = 10;
    private double refillPerSecond = 1.0;
    private boolean failOpen = true;
    private String keyPrefix = "rl";

    /** Build the numeric rule the active algorithm needs from whichever fields apply to it. */
    public RateLimitRule toRule() {
        return switch (algorithm) {
            case TOKEN_BUCKET, LEAKY_BUCKET -> RateLimitRule.bucket(capacity, refillPerSecond);
            default -> RateLimitRule.perWindow(limit, window);
        };
    }

    public RateLimitAlgorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(RateLimitAlgorithm algorithm) { this.algorithm = algorithm; }
    public Backend getBackend() { return backend; }
    public void setBackend(Backend backend) { this.backend = backend; }
    public long getLimit() { return limit; }
    public void setLimit(long limit) { this.limit = limit; }
    public Duration getWindow() { return window; }
    public void setWindow(Duration window) { this.window = window; }
    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }
    public double getRefillPerSecond() { return refillPerSecond; }
    public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
